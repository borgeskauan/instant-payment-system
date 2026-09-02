# Entrega recuperável de notificações

Este documento responde a uma pergunta: **como uma confirmação continua recuperável depois que a transação financeira termina?**

Seu escopo começa quando o SPI cria uma obrigação de notificar na mesma transação do pagamento e termina no protocolo pelo qual uma instituição avança seu próprio progresso de consumo. As invariantes financeiras que produzem essa obrigação estão em [Corretude do pagamento](payment-correctness.md).

## O contrato

A entrega preserva estas propriedades:

1. uma transação financeira concluída deixa uma obrigação durável de informar seu resultado;
2. uma falha entre PostgreSQL e Kafka não apaga silenciosamente essa obrigação;
3. depois da publicação confirmada, o histórico permanece recuperável durante a retenção operacional;
4. o Gateway pode repetir fisicamente uma confirmação, mas não pode inventar que a instituição já a processou;
5. memória acelera a leitura recente, mas não participa da corretude.

O sistema oferece entrega **at-least-once**, não exactly-once. Duplicatas físicas são permitidas; perda silenciosa dentro das condições documentadas não é.

## Da transação financeira ao Kafka

PostgreSQL e Kafka não compartilham uma transação. Publicar diretamente depois de alterar o pagamento abriria esta janela:

```text
COMMIT do pagamento
        ↓
processo cai antes da publicação
        ↓
resultado existe, mas ninguém pode recuperá-lo
```

A transactional outbox fecha essa janela:

```text
transação PostgreSQL

├── pagamento e saldos
├── auditoria
└── notification_outbox
          │
          │ depois do commit
          ▼
        Kafka
```

Cada row da outbox contém somente:

| Campo | Função |
| --- | --- |
| `communication_id` | identidade estável do envelope de notificação |
| `recipient_ispb` | instituição destinatária e chave de particionamento |
| `payload` | bytes exatos que serão publicados |
| `created_at` | ordem de recuperação no startup |

Não há status de publicação, contador de tentativas, lease ou ACK na tabela. A presença da row significa apenas: **esta obrigação ainda não teve sua publicação confirmada e removida**.

### Fast path após o commit

A mesma transação que persiste a outbox publica um evento interno. O listener desse evento só roda depois do commit e entrega o lote a uma fila bounded do publisher.

Isso evita consultar a outbox no caminho saudável:

```text
COMMIT
  ↓
evento AFTER_COMMIT
  ↓
fila bounded
  ↓
publisher único
  ↓
Kafka
```

O evento em memória não é a garantia de durabilidade. Se a transação for revertida, nem a row nem o evento pós-commit sobrevivem. Se a admissão no fast path falhar depois do commit, a row já persistida continua sendo a autoridade.

### Confirmação e repetição do lote

O publisher envia os bytes armazenados usando `recipient_ispb` como chave Kafka e `communication_id` como header. O producer usa `acks=all` e idempotência do Kafka.

A outbox só é apagada depois que todas as publicações do lote são confirmadas pelo broker.

Se uma publicação falhar, ficar inconclusiva ou se o delete da outbox falhar, o lote inteiro é retentado. Algumas mensagens já podem ter chegado ao Kafka; por isso, essa regra evita perda ao custo de possíveis duplicatas.

A idempotência do producer reduz duplicatas causadas por retries internos de uma sessão Kafka. Ela não transforma o handoff completo em exactly-once: uma nova tentativa feita pela aplicação ainda pode publicar novamente um envelope que o broker já recebeu.

### Recuperação da outbox

Antes de iniciar os consumers de pagamentos, o SPI lê as rows mais antigas da outbox, publica os lotes e só então libera o processamento de novas entradas. Isso impede que um restart deixe obrigações antigas indefinidamente atrás de tráfego novo.

Durante o runtime, o worker mantém o lote atual e o retenta até obter confirmação e apagá-lo.

Existe uma limitação explícita no desenho vigente: se uma row foi commitada, mas o evento pós-commit não conseguiu entrar na fila, não há hoje uma varredura periódica que a redescubra enquanto a instância continua rodando. A obrigação permanece no PostgreSQL e será recuperada no próximo startup, mas a recuperação em runtime ainda é trabalho pendente.

## Kafka como histórico operacional

Depois que o broker confirma a publicação e a outbox é removida, Kafka passa a ser a autoridade sobre as confirmações disponíveis para recuperação.

O tópico `psp-notifications-v1` possui, no ambiente atual:

* oito partições fixas;
* retenção de sete dias;
* `recipient_ispb` como chave;
* um broker e fator de replicação 1 no ambiente local qualificado.

Usar o destinatário como chave faz com que todas as notificações de um PSP sejam publicadas na mesma partição enquanto o número de partições permanecer fixo. PSPs diferentes podem compartilhar uma partição por colisão do hash; isso não mistura suas mensagens no protocolo, mas influencia o formato do cursor.

O número de partições faz parte do desenho desta geração do tópico. Alterá-lo exige uma migração explícita de geração e cursor; não é uma mudança operacional transparente.

Kafka não decide estado financeiro, não sabe o que a instituição já processou e não participa da transação do pagamento. Sua autoridade começa no histórico publicado.

## O cursor pertence ao progresso da instituição

O Notification Gateway expõe um `PullNotifications` unary sobre gRPC. A instituição envia o último cursor processado e recebe até 15 notificações mais um próximo cursor.

```text
Pull(cursor anterior)
        ↓
até 15 notificações
+
nextCursor
```

O cursor é opaco e autenticado com HMAC. Seu conteúdo vincula:

```text
versão do formato
geração do tópico
ISPB autenticado
partição
último offset examinado
```

Por isso, uma instituição não pode reutilizar o cursor de outra, trocar a partição ou avançar livremente o offset sem invalidar a assinatura.

O Gateway é autoridade sobre a validade do cursor que emitiu. A instituição é autoridade sobre a afirmação: **processei duravelmente até este cursor**.

O protocolo exige que o cursor novo só seja persistido depois que todo o lote correspondente tiver sido processado de forma durável. O core não consegue tornar o armazenamento interno da instituição durável; ele fornece a fronteira necessária para que ela faça isso corretamente.

Se a instituição falhar antes de persistir o novo cursor, reapresenta o anterior e pode receber o mesmo envelope novamente. `communication_id` permanece igual nas republicações da mesma obrigação e permite que o consumidor reconheça a duplicata.

### Por que o cursor guarda o último offset examinado

Uma partição pode conter registros de vários PSPs:

```text
offset 100 → PSP A
offset 101 → PSP B
offset 102 → PSP A
```

Ao atender o PSP A, o Gateway retorna os registros 100 e 102, mas também registra que examinou o offset 101. O próximo cursor pode, portanto, apontar para 102.

Se guardasse apenas o último offset pertencente ao PSP, o Gateway precisaria reexaminar indefinidamente registros de outros participantes. Se avançasse sem observar todos os offsets intermediários, poderia saltar uma notificação válida.

A leitura para quando encontra a décima quinta notificação do PSP ou atinge o limite de varredura. O cursor nunca avança além do último registro realmente examinado.

## Memória no caminho saudável, Kafka no fallback

O Gateway acompanha as partições do tópico e mantém uma janela recente bounded em memória para cada uma. A janela guarda todos os registros, inclusive os destinados a outros PSPs, porque precisa preservar a continuidade dos offsets examinados.

Quando a janela contém uma sequência contínua depois do cursor, o Pull é respondido sem uma leitura histórica no Kafka.

```text
tailer Kafka
    ↓
janela recente por partição
    ↓
Pull
```

Se o cursor estiver antes da janela, se houve eviction ou se existir uma lacuna, o Gateway usa um consumer histórico com `assign + seek` e responde diretamente do Kafka:

```text
cache miss
    ↓
seek no offset seguinte ao cursor
    ↓
filtra o PSP dentro da partição
    ↓
responde ao Pull
```

Não existe uma etapa separada de reidratação. O fallback atende aquele Pull; o tailer normal continua alimentando a memória recente.

Quando o cursor já está na cauda conhecida e não há notificação disponível, o Gateway mantém o RPC aberto por até 30 segundos. A chegada de um batch Kafka destinado ao PSP acorda o Pull; o timeout retorna uma resposta vazia com o mesmo cursor.

Só um Pull pode permanecer ativo por PSP. Uma segunda chamada concorrente é rejeitada, evitando dois fluxos competindo pelo mesmo progresso lógico.

## Falhas na fronteira de entrega

| Situação | Resultado |
| --- | --- |
| rollback da transação financeira | a obrigação de notificar também é revertida |
| commit seguido de queda antes da publicação | a row permanece na outbox e é recuperada no startup |
| confirmação Kafka parcial ou inconclusiva | o lote inteiro pode ser publicado novamente |
| Kafka confirmou, mas o delete falhou | o lote inteiro pode ser publicado novamente |
| Gateway reiniciou ou perdeu a janela em memória | o Pull lê o histórico diretamente do Kafka |
| instituição falhou antes de persistir o cursor | o cursor anterior pode produzir nova entrega |
| cursor aponta para dados removidos pela retenção | o Pull falha explicitamente; a recuperação deixa o fluxo operacional normal |
| cursor foi adulterado ou pertence a outro PSP | o Pull é rejeitado |

Essas regras concentram a ambiguidade onde ela é segura: uma fronteira inconclusiva pode repetir uma confirmação, mas não declara por conta própria que ela foi processada.

## Onde cada garantia vive

| Propriedade | Autoridade ou mecanismo |
| --- | --- |
| obrigação nasce com o fato financeiro | transação PostgreSQL e outbox |
| bytes e identidade permanecem estáveis durante retries | row imutável da outbox |
| publicação só libera a outbox depois da confirmação | publisher do SPI |
| histórico operacional recuperável | Kafka |
| progresso efetivamente processado | instituição participante |
| cursor não pode ser inventado ou transferido | HMAC e vínculo ao ISPB/partição |
| caminho recente sem leitura histórica | janela bounded do Gateway |
| cache miss ou restart | leitura direta do Kafka |

## Limites assumidos

O contrato atual aceita estes limites:

* a retenção operacional é de sete dias; recuperação mais antiga pertence a disaster recovery;
* o ambiente qualificado possui um broker e fator de replicação 1, portanto não demonstra alta disponibilidade do Kafka;
* o tópico possui oito partições fixas e não suporta reparticionamento transparente;
* existe um fluxo lógico e no máximo um Pull ativo por PSP;
* o Gateway não persiste ACK, cursor ou estado individual de entrega;
* não há backpressure do transporte de notificações sobre a admissão financeira;
* a redescoberta de uma row que perdeu o fast path pós-commit ainda depende de restart.

Dentro dessas condições, a propriedade central é: **depois que a transação cria uma confirmação, o sistema preserva um caminho recuperável para entregá-la, aceitando repetição física quando uma fronteira fica inconclusiva**.

## Verificação no repositório

O handoff entre PostgreSQL e Kafka pode ser inspecionado em:

* [`NotificationObligationService`](../../spi/src/main/java/br/kauan/spi/application/notification/NotificationObligationService.java);
* [`NotificationOutboxPipeline`](../../spi/src/main/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipeline.java);
* [`OutboundNotificationFastPathIntegrationTest`](../../spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationFastPathIntegrationTest.java);
* [`NotificationOutboxPipelineTest`](../../spi/src/test/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipelineTest.java).

O protocolo de Pull, cursor e fallback pode ser verificado em:

* [`NotificationGrpcService`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java);
* [`DeliveryCursorCodec`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/DeliveryCursorCodec.java);
* [`RecentNotificationWindow`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/RecentNotificationWindow.java);
* [`HistoricalKafkaReader`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReader.java);
* [`HistoricalKafkaReaderTest`](../../notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReaderTest.java).
