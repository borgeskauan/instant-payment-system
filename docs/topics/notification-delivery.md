# Como uma confirmação continua recuperável

Este documento responde a uma pergunta:

> Depois que o dinheiro já foi movimentado, o que impede a confirmação de desaparecer antes de chegar à instituição?

O caminho começa quando o Payment Processor grava a obrigação de notificar junto com o pagamento. Termina quando a instituição processa a confirmação e avança seu próprio progresso. As regras financeiras anteriores a essa etapa estão em [Como o pagamento permanece correto](payment-correctness.md).

## O que a entrega promete

A entrega preserva estas propriedades:

1. uma transação financeira concluída deixa uma obrigação durável de informar seu resultado;
2. uma falha entre PostgreSQL e Kafka não apaga silenciosamente essa obrigação;
3. depois da publicação confirmada, o histórico permanece recuperável durante a retenção operacional;
4. o Gateway pode repetir fisicamente uma confirmação, mas não pode inventar que a instituição já a processou;
5. memória acelera a leitura recente, mas não participa da corretude.

O sistema oferece entrega **at-least-once**, não exactly-once. Em outras palavras: uma confirmação pode aparecer novamente, mas não pode desaparecer silenciosamente dentro das condições documentadas.

## O primeiro risco está entre PostgreSQL e Kafka

PostgreSQL e Kafka não compartilham uma transação. Publicar diretamente depois de alterar o pagamento abriria esta janela:

```text
COMMIT do pagamento
        ↓
processo cai antes da publicação
        ↓
resultado existe, mas ninguém pode recuperá-lo
```

Para fechar essa janela, a confirmação é gravada primeiro no próprio PostgreSQL, dentro de uma **transactional outbox**:

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

Cada registro da outbox contém somente:

| Campo | Função |
| --- | --- |
| `communication_id` | identidade estável do envelope de notificação |
| `recipient_ispb` | instituição destinatária e chave de particionamento |
| `payload` | bytes exatos que serão publicados |
| `created_at` | ordem de recuperação na próxima inicialização |

Não há status de publicação, contador de tentativas ou controle individual de ACK na tabela. A presença do registro significa apenas: **esta obrigação ainda não teve sua publicação confirmada e removida**.

### O caminho normal não precisa consultar a tabela novamente

A transação que grava a outbox também agenda um evento interno. Ele só é entregue depois do commit e encaminha o lote ao publicador.

Isso evita consultar a outbox no caminho saudável:

```text
COMMIT
  ↓
evento AFTER_COMMIT
  ↓
fila limitada
  ↓
publicador único
  ↓
Kafka
```

Esse evento em memória é apenas o caminho rápido. Se a transação for revertida, nem o registro nem o evento sobrevivem. Se o encaminhamento falhar depois do commit, a obrigação já persistida continua sendo a fonte de verdade.

### A outbox só é removida depois da confirmação

O publicador envia exatamente os bytes armazenados. A instituição destinatária define a chave Kafka, e a identidade da comunicação acompanha a mensagem. Ele espera a confirmação configurada como `acks=all` e usa a idempotência do Kafka.

A outbox só é apagada depois que todas as publicações do lote são confirmadas pelo broker.

Se uma publicação falhar, ficar inconclusiva ou se a remoção da outbox falhar, o lote inteiro é enviado novamente. Algumas mensagens já podem ter chegado ao Kafka; por isso, essa regra evita perda ao custo de possíveis duplicatas.

A idempotência do producer Kafka reduz duplicatas causadas por suas próprias tentativas internas. Ela não torna toda a fronteira exactly-once: a aplicação ainda pode reenviar algo que o broker recebeu antes de a resposta se perder.

### O que acontece depois de uma reinicialização

Antes de aceitar novos lotes de pagamento, o Payment Processor lê as obrigações mais antigas da outbox e tenta publicá-las. Isso impede que uma reinicialização deixe confirmações antigas indefinidamente atrás de tráfego novo.

Enquanto a aplicação está rodando, o publicador mantém o lote atual e tenta novamente até conseguir confirmá-lo e removê-lo.

Existe uma limitação explícita: se a obrigação foi confirmada no banco, mas seu evento pós-commit não chegou ao publicador, não há hoje uma busca periódica durante a mesma execução. Ela continua segura no PostgreSQL, porém só volta ao fluxo na próxima inicialização.

## Depois da publicação, Kafka guarda o histórico

Depois que o broker confirma a publicação e a outbox é removida, Kafka passa a ser a autoridade sobre as confirmações disponíveis para recuperação.

O tópico `psp-notifications-v1` possui, no ambiente atual:

* oito partições fixas;
* retenção de sete dias;
* `recipient_ispb` como chave;
* um broker e fator de replicação 1 no ambiente local qualificado.

Usar o destinatário como chave mantém todas as notificações de uma instituição na mesma partição enquanto o número de partições permanecer fixo. Instituições diferentes podem compartilhar uma partição; o Gateway ainda separa suas mensagens, mas o cursor precisa representar essa leitura compartilhada.

O número de partições faz parte do desenho desta geração do tópico. Alterá-lo exige uma migração explícita de geração e cursor; não é uma mudança operacional transparente.

Kafka não decide estado financeiro, não sabe o que a instituição já processou e não participa da transação do pagamento. Sua autoridade começa no histórico publicado.

## A instituição decide até onde processou

Para buscar notificações, a instituição envia ao Gateway seu último cursor processado. A resposta contém até 15 mensagens e um novo cursor.

```text
Pull(cursor anterior)
        ↓
até 15 notificações
+
nextCursor
```

### O primeiro Pull

Não existe um cursor criado durante o onboarding. Na primeira chamada, a instituição envia o campo vazio:

```text
Pull(cursor vazio)
        ↓
começa no primeiro offset ainda retido
da partição daquela instituição
```

O Gateway não começa em “agora”. Ele examina o histórico disponível desde o ponto mais antigo que o Kafka ainda preserva e devolve as notificações destinadas à instituição. Portanto, uma instituição que inicia o consumo recebe também seu backlog dentro da janela de retenção.

Se nenhum registro estiver disponível, a chamada segue o long polling normal e pode terminar com um lote e um cursor ainda vazios. O primeiro cursor assinado é emitido quando o Gateway efetivamente examina algum registro da partição, mesmo que esse avanço inclua mensagens destinadas a outras instituições.

Essa regra não recupera mensagens anteriores à retenção do tópico. O primeiro Pull começa no início do histórico **ainda disponível**, não no início absoluto da vida da instituição.

Dentro de uma partição, Kafka identifica cada mensagem por uma posição numérica chamada **offset**. O cursor é um token opaco assinado pelo Gateway que vincula:

```text
versão do formato
geração do tópico
ISPB autenticado
partição
último offset examinado
```

Por isso, uma instituição não pode usar o cursor de outra ou avançar sua posição por conta própria sem invalidar a assinatura.

O Gateway garante que o cursor representa uma posição que realmente emitiu. A instituição decide quando pode afirmar: **processei de forma durável até aqui**.

O protocolo exige que o cursor novo só seja persistido depois que todo o lote correspondente tiver sido processado de forma durável. O core não consegue tornar o armazenamento interno da instituição durável; ele fornece a fronteira necessária para que ela faça isso corretamente.

Se a instituição falhar antes de persistir o novo cursor, reapresenta o anterior e pode receber o mesmo envelope novamente. `communication_id` permanece igual nas republicações da mesma obrigação e permite que o consumidor reconheça a duplicata.

### Por que o cursor avança também sobre mensagens de outras instituições

Uma partição pode conter registros de várias instituições:

```text
offset 100 → instituição A
offset 101 → instituição B
offset 102 → instituição A
```

Ao atender a instituição A, o Gateway devolve os registros 100 e 102, mas também sabe que examinou o offset 101. O próximo cursor pode, portanto, apontar para 102.

Se guardasse apenas a última mensagem da instituição A, o Gateway examinaria repetidamente as mensagens dos outros participantes. Por outro lado, avançar sem ler os offsets intermediários poderia saltar uma mensagem válida.

A leitura para quando encontra a décima quinta notificação da instituição ou atinge o limite de varredura. O cursor nunca avança além do último registro realmente examinado.

## Memória acelera; Kafka recupera

O Gateway acompanha as partições e mantém em memória uma janela limitada das mensagens mais recentes. Ela inclui mensagens de todas as instituições que compartilham a partição, preservando a sequência dos offsets.

Quando a janela contém uma sequência contínua depois do cursor, o Pull é respondido sem uma leitura histórica no Kafka.

```text
leitura contínua do Kafka
    ↓
janela recente por partição
    ↓
Pull
```

Se o cursor apontar para algo mais antigo ou a memória não contiver uma sequência completa, o Gateway lê diretamente do Kafka:

```text
mensagem fora da janela em memória
    ↓
lê a partir da posição seguinte ao cursor
    ↓
filtra a instituição dentro da partição
    ↓
responde ao Pull
```

Não existe uma reconstrução separada do cache. A leitura histórica responde àquela chamada, enquanto o fluxo normal continua alimentando a memória recente.

Quando o cursor já está no ponto mais recente conhecido e não há notificação disponível, o Gateway mantém a chamada aberta por até 30 segundos. A chegada de mensagens para aquela instituição libera a resposta; o timeout devolve um lote vazio com o mesmo cursor.

Só um Pull pode permanecer ativo por instituição. Uma segunda chamada concorrente é rejeitada, evitando dois fluxos competindo pelo mesmo progresso lógico.

## O que acontece quando alguma fronteira falha

| Situação | Resultado |
| --- | --- |
| rollback da transação financeira | a obrigação de notificar também é revertida |
| commit seguido de queda antes da publicação | o registro permanece na outbox e é recuperado na próxima inicialização |
| confirmação Kafka parcial ou inconclusiva | o lote inteiro pode ser publicado novamente |
| Kafka confirmou, mas a remoção da outbox falhou | o lote inteiro pode ser publicado novamente |
| Gateway reiniciou ou perdeu a janela em memória | o Pull lê o histórico diretamente do Kafka |
| instituição falhou antes de persistir o cursor | o cursor anterior pode produzir nova entrega |
| cursor aponta para dados removidos pela retenção | o Pull falha explicitamente; a recuperação deixa o fluxo operacional normal |
| cursor foi adulterado ou pertence a outra instituição | o Pull é rejeitado |

Quando uma fronteira fica inconclusiva, o sistema prefere repetir a confirmação. Ele nunca conclui sozinho que a instituição a processou.

## Como cada promessa é protegida

| Propriedade | Autoridade ou mecanismo |
| --- | --- |
| obrigação nasce com o fato financeiro | transação PostgreSQL e outbox |
| bytes e identidade permanecem estáveis durante novas tentativas | registro imutável da outbox |
| publicação só libera a outbox depois da confirmação | publicador do Payment Processor |
| histórico operacional recuperável | Kafka |
| progresso efetivamente processado | instituição participante |
| cursor não pode ser inventado ou transferido | HMAC e vínculo ao ISPB/partição |
| caminho recente sem leitura histórica | janela limitada do Gateway |
| mensagem antiga ou reinicialização | leitura direta do Kafka |

## Onde este contrato termina

O contrato atual aceita estes limites:

* a retenção operacional é de sete dias; recuperar algo mais antigo exige um procedimento extraordinário;
* o ambiente qualificado possui um broker e fator de replicação 1, portanto não demonstra alta disponibilidade do Kafka;
* o tópico possui oito partições fixas e não suporta reparticionamento transparente;
* existe um fluxo lógico e no máximo um Pull ativo por instituição;
* o Gateway não persiste ACK, cursor ou estado individual de entrega;
* uma indisponibilidade prolongada da entrega não reduz automaticamente a admissão de novos pagamentos;
* encontrar novamente uma obrigação que perdeu o encaminhamento pós-commit ainda depende de uma reinicialização.

Dentro dessas condições, a propriedade central é: **depois que a transação cria uma confirmação, o sistema preserva um caminho recuperável para entregá-la, aceitando repetição física quando uma fronteira fica inconclusiva**.

## Verificar no código

A passagem entre PostgreSQL e Kafka pode ser inspecionada em:

* [`NotificationObligationService`](../../spi/src/main/java/br/kauan/spi/application/notification/NotificationObligationService.java);
* [`NotificationOutboxPipeline`](../../spi/src/main/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipeline.java);
* [`OutboundNotificationFastPathIntegrationTest`](../../spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationFastPathIntegrationTest.java);
* [`NotificationOutboxPipelineTest`](../../spi/src/test/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipelineTest.java).

O protocolo de Pull, cursor e leitura histórica pode ser verificado em:

* [`NotificationGrpcService`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java);
* [`DeliveryCursorCodec`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/DeliveryCursorCodec.java);
* [`RecentNotificationWindow`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/RecentNotificationWindow.java);
* [`HistoricalKafkaReader`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReader.java);
* [`HistoricalKafkaReaderTest`](../../notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReaderTest.java).
