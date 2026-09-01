# Kafka como log durável de notificações

## Status

Desenho aprovado em 24 de agosto de 2026. Este documento registra a decisão
arquitetural; a implementação será detalhada em um plano separado.

## Objetivo

Reduzir a pressão de CPU, WAL, índices e contenção no PostgreSQL removendo dele
o armazenamento histórico e a projeção de entrega de notificações. Depois de
um handoff confiável, Kafka passa a ser o único log operacional de entrega por
uma janela de sete dias. O PSP mantém seu progresso por cursor e continua
recebendo notificações com semântica at-least-once.

O desenho preserva no PostgreSQL somente a responsabilidade que precisa ser
atômica com o negócio financeiro: criar a obrigação de notificação. Não busca
alta disponibilidade física no MVP.

## Contexto e motivação

O desenho atual tenta combinar:

- `outbound_notification` como fonte durável no PostgreSQL;
- Kafka como caminho rápido best effort;
- `delivery_index` como segunda ordem durável, local a cada PSP;
- memória como cache do Gateway;
- reconciler e checkpoint para manter as duas persistências coerentes.

Isso produz duas ordens, duas representações duráveis e um caminho de
reconciliação. Os benchmarks da estabilização mostraram que o PostgreSQL já é
o recurso dominante e que inserts, índices, WAL, scans de reconciliação e o
contador global de `outbound_position` competem com as transações financeiras.

A workload de notificações é append-only, sequencial, reexecutável e limitada
por retenção. Esse formato é mais adequado ao Kafka que ao PostgreSQL. A
decisão é manter as invariantes financeiras no PostgreSQL e transferir
armazenamento, ordenação, replay e retenção de notificações para Kafka.

## Arquitetura selecionada

```text
PostgreSQL
payment + audit + notification_outbox
                |
                v
fila limitada em memória no SPI
                |
                v
publisher único
                |
                v
Kafka: psp-notifications-v1
                |
                v
tailer + ring buffer por partição
                |
                v
Pull(cursor)
                |
                v
PSP
```

As responsabilidades são exclusivas:

```text
row na outbox
= a obrigação ainda não foi transferida duravelmente ao Kafka

registro no Kafka
= a obrigação está disponível para entrega por sete dias

cursor durável do PSP
= o PSP processou todos os registros relevantes examinados até essa posição
```

O mesmo dado não permanece historicamente no PostgreSQL e no Gateway. Depois
do ACK do broker, a outbox é removida e Kafka assume a obrigação de entrega.

## Outbox transacional mínima

O SPI persiste, na mesma transação financeira:

```text
notification_outbox
-------------------
communication_id   chave primária
recipient_ispb
payload
created_at
```

Não existem:

- posição de saída;
- status `PENDING` ou `PUBLISHED`;
- contador de tentativas;
- lease;
- próximo instante de tentativa;
- erro da última publicação;
- timestamp de publicação.

Uma falha no insert da outbox reverte a transação financeira correspondente.
O payload e `communication_id` são construídos uma única vez; retries publicam
os mesmos bytes e a mesma identidade.

## Publisher único e fast path em memória

O MVP possui uma única instância do SPI e um único publisher. A concorrência
entre múltiplas instâncias pertence a um trabalho futuro de alta
disponibilidade.

Na inicialização:

```text
SPI inicia
-> publisher lê a outbox em batches
-> publica cada batch
-> remove batches confirmados
-> considera o publisher pronto
-> inicia os listeners financeiros
```

O backlog existente precisa ser drenado antes que os listeners PACS.008 e
PACS.002 passem a criar novas obrigações. Se Kafka estiver indisponível, o SPI
não começa a consumir novos batches.

Depois da inicialização, o caminho normal não consulta a outbox:

```text
COMMIT
-> evento after-commit contém o batch completo
-> put bloqueante em fila limitada
-> publisher envia ao Kafka
-> aguarda todos os ACKs
-> DELETE batcheado da outbox
```

A fila guarda batches imutáveis de uma transação financeira. Ela é limitada e
configurável. A capacidade é parâmetro interno de operação, não parte do
contrato de workload; o plano de implementação deve escolher o valor inicial
com base no tamanho observado dos batches e no orçamento de memória vigente.

Fila cheia aplica backpressure no callback after-commit. A transação já foi
confirmada, portanto nenhum lock financeiro permanece retido; o listener Kafka
de entrada apenas deixa de avançar até que o publisher libere capacidade.
Esse comportamento impede que uma indisponibilidade do Kafka transforme o
PostgreSQL em backlog ilimitado.

Não há polling periódico nem modo de recovery em runtime. As falhas são
observáveis e determinísticas:

- crash antes do enqueue: a row permanece e é encontrada no próximo startup;
- fila cheia: o produtor espera;
- falha Kafka: o worker retém o batch e faz retry com backoff;
- falha no delete: o batch não é considerado concluído;
- morte do worker: o SPI fica unhealthy, interrompe novos consumos e deve ser
  reiniciado;
- shutdown: itens abandonados na memória permanecem na outbox e são recuperados
  no próximo startup.

## Unidade de confirmação do publisher

O publisher envia todos os registros de um batch e espera todos os resultados.

```text
todos os registros confirmados pelo broker
-> delete do batch inteiro em uma transação

qualquer falha ou resultado inconclusivo
-> nenhum delete
-> retry do batch inteiro
```

Não há bookkeeping de sucesso parcial. Registros já confirmados podem ser
publicados novamente no retry. Essa duplicação é permitida pela semântica
at-least-once e mantém `communication_id` e payload idênticos.

Uma transação Kafka não é necessária: perda é proibida, mas duplicação física
é permitida. Um crash entre o ACK de todos os registros e o delete da outbox
também republica o batch integralmente.

## Kafka como log autoritativo

O tópico inicial é:

```text
nome: psp-notifications-v1
partições: 8
chave: recipient_ispb
retenção: 7 dias
```

As oito partições ficam congeladas durante toda a geração `v1`. O inicializador
de tópicos deve rejeitar uma topologia incompatível, em vez de aumentar o
número de partições silenciosamente. Uma mudança futura cria nova geração de
tópico e de cursor; cursores antigos continuam vinculados à geração anterior
durante a respectiva retenção.

O producer usa `acks=all` e idempotência nativa habilitada. No ambiente MVP há
um broker e replication factor 1. Portanto, `acks=all` confirma somente a cópia
existente; não há tolerância à perda do broker, host ou volume.

Kafka é o proprietário da obrigação somente depois do ACK do broker. Antes
disso, a outbox continua sendo a fonte de recuperação.

## Limitação de alta disponibilidade do MVP

O MVP valida o processo lógico com:

- um SPI e um publisher;
- um Notification Gateway;
- um broker Kafka;
- replication factor 1;
- volumes persistentes;
- restart de processos preservando os volumes.

Ele não valida:

- perda de broker, host ou volume;
- quorum de controllers;
- failover de PostgreSQL;
- múltiplas instâncias de SPI ou Gateway;
- rebalanceamento durante falhas distribuídas;
- partições de rede.

Um trabalho futuro de alta disponibilidade deverá levantar os requisitos e
validar, no mínimo, replicação do Kafka, `min.insync.replicas`, múltiplas
instâncias dos componentes, distribuição de réplicas e failover. Essa limitação
não deve ser escondida por alegações genéricas de durabilidade do MVP.

## Cursor externo

O cursor continua opaco e autenticado pelo Gateway. Seu conteúdo conceitual é:

```text
recipient_ispb
topic_generation
partition
last_examined_offset
MAC
```

Kafka não conhece o cursor do PSP. O Gateway traduz o token para uma leitura do
broker. O token é vinculado ao PSP autenticado e à geração do tópico; alteração,
uso por outro PSP, partição incompatível ou offset futuro são rejeitados.

O offset representa o último registro da partição examinado para aquele PSP,
não necessariamente a última notificação devolvida. Exemplo:

```text
101 -> PSP A
102 -> PSP B
103 -> PSP A
104 -> PSP C

Pull do PSP A depois de 100
-> entrega 101 e 103
-> pode devolver cursor 104
```

Isso impede que o Gateway releia posições que já sabe não conter notificações
para o PSP. Ao encontrar o décimo quinto registro destinado ao PSP, a leitura
para nesse offset e não examina registros posteriores que não caberão na
resposta.

O contrato mantém:

- gRPC unary long-poll;
- no máximo 15 notificações por resposta;
- um Pull simultâneo por PSP;
- PSP persiste todo o lote e o novo cursor antes do próximo Pull;
- ausência de cursor representa o início ainda retido do fluxo;
- entrega at-least-once.

O número de registros Kafka examinados por uma chamada é limitado internamente
por um parâmetro configurável. Ao atingir esse orçamento antes de coletar 15
notificações, o Gateway responde com o que encontrou e avança o cursor até o
último offset examinado. O PSP pode emitir imediatamente o próximo Pull. Esse
limite protege o Gateway quando muitos PSPs compartilham a mesma partição e não
altera o limite público de 15 notificações. O plano de implementação deve
escolher e testar o valor inicial sem transformá-lo em contrato do protocolo.

## Tailer e ring buffer

O Gateway mantém um tailer contínuo das oito partições. O tailer lê cada
registro novo uma vez e o coloca em um ring buffer compartilhado pela
partição. Cada entrada contém:

```text
offset
recipient_ispb
communication_id
payload
```

O ring buffer é uma janela circular em memória com capacidade configurável por
partição. Ao atingir a capacidade, o registro mais antigo é descartado. Essa
capacidade é tuning interno e pode mudar depois de benchmark sem alterar o
protocolo. O plano de implementação deve escolher o valor inicial a partir da
taxa observada de notificações, da duração útil desejada para o cache e do
orçamento de memória do Gateway.

PSPs diferentes na mesma partição filtram o mesmo buffer. Portanto, no caminho
saudável, cada registro Kafka é lido uma única vez pelo tailer, mesmo que vários
PSPs avancem sobre o intervalo.

O buffer mantém os limites de cobertura por partição. Um Pull pode responder da
RAM apenas quando todo o intervalo necessário depois do cursor está coberto.
Quando o cursor está no final conhecido, o Pull aguarda o tailer por até o
timeout existente de 30 segundos.

## Cache miss histórico

Quando o cursor é anterior ao menor offset ainda presente no ring buffer, o
Gateway lê diretamente a partição Kafka a partir de `cursor + 1`, filtra pelo
PSP e aplica o mesmo orçamento de scan e o mesmo limite de resposta.

Leituras históricas de PSPs diferentes podem reler partes da mesma partição.
Esse custo é aceito no MVP porque o caso representa restart, desconexão longa
ou atraso substancial, e não o caminho saudável de 2.000 TPS. Não haverá:

- RocksDB;
- cache persistente;
- índice histórico local;
- coordenador sofisticado de catch-up;
- reconstrução obrigatória da RAM após restart.

Se benchmarks mostrarem reread material no recovery, um cache efêmero de blocos
por partição pode ser avaliado separadamente.

## Retenção e cursor expirado

Kafka preserva registros por sete dias. Essa é uma janela de recuperação
automática, não um SLA de latência.

```text
cursor dentro da retenção
-> retomada automática at-least-once

cursor anterior ao log-start-offset
-> CURSOR_EXPIRED
-> não inicia silenciosamente no primeiro offset disponível
```

Indisponibilidade superior a sete dias exige um procedimento de disaster
recovery e reconciliação de estado fora do protocolo normal. Retenção
indefinida, object storage, snapshots de recuperação e resync administrativo
automatizado estão fora do MVP.

## Duplicatas

Uma `communication_id` pode aparecer em mais de um offset por causa de:

- crash entre ACK Kafka e delete da outbox;
- retry após resultado Kafka inconclusivo;
- retry físico do producer.

O Gateway entrega essas ocorrências. Não existe deduplicação durável nem
`delivery_index`. O PSP aplica idempotência por `communication_id`. A autoridade
do cursor é o offset examinado, portanto cada ocorrência física continua
avançando normalmente pelo log.

## Comportamento sob indisponibilidade

Kafka indisponível durante a inicialização impede readiness do publisher e o
início dos listeners financeiros.

Kafka indisponível durante operação faz o publisher reter o batch, aplicar
retry e deixar a fila limitada encher. Quando cheia, o after-commit bloqueia e
os consumers do SPI deixam de avançar. O Kafka de ingresso ainda pode acumular
registros; propagação de backpressure até o serviço HTTP de ingresso é um
problema separado e fica fora desta mudança.

Gateway indisponível não impede o publisher: Kafka preserva as notificações.
PSP indisponível não exige estado no Gateway: o PSP reapresenta seu último
cursor durável ao retornar.

## Remoções

A migração remove toda implementação exclusiva do modelo híbrido:

- `outbound_position` e seu contador global;
- `delivery_position`;
- `delivery_index`;
- checkpoint e reconciler;
- datasource, repositories e migrations do Gateway, se não houver outra
  responsabilidade persistente;
- fallback do Pull para PostgreSQL;
- lifecycle de publicação best effort atual;
- configurações, métricas e testes vinculados apenas a esses mecanismos.

O nome persistente volta a expressar seu papel temporário: `notification_outbox`.
Uma migração online de notificações históricas não faz parte do MVP; os testes e
benchmarks usam ambiente recriado. Essa limitação precisa permanecer explícita.

## Alternativas consideradas

### Manter o híbrido atual

Preserva cursor lógico independente do Kafka e usa o PostgreSQL como fonte
durável. Foi rejeitado porque mantém duas ordens persistidas, reconciler,
anti-join, contador global e pressão mensurável sobre o banco financeiro.

### PostgreSQL como log direto de Pull

Elimina a projeção no Gateway, mas transfere todas as leituras históricas e de
Pull para o PostgreSQL. Uma sequência segura exige serialização global ou por
PSP; identificadores como sequence, UUIDv7 ou Snowflake não impedem inversão de
ordem de commit. Foi rejeitado porque o PostgreSQL já é o recurso limitante.

### Posição por PSP atribuída no SPI

Permite cache com lacunas isoladas e cursor lógico direto, mas move locks de
ordenação para as transações financeiras. PACS.008 e PACS.002 produzem
notificações para PSPs coincidentes e podem disputar esses contadores. Foi
rejeitado para não ampliar o hot path financeiro.

### Contador global no SPI

É simples para reconciliação e cursor global, mas serializa transações sem
relação entre PSPs. O experimento atual já mostrou contenção nesse contador.
Foi rejeitado.

### CDC do WAL para Kafka

Evita polling e outbox publisher na aplicação, mas introduz replication slot,
connector, offsets, retenção de WAL, operação e recuperação de mais um
subsistema. Foi rejeitado por complexidade arquitetural excessiva para o MVP.

### Cursor lógico materializado fora do PostgreSQL

Kafka Streams/RocksDB ou tópico compactado poderia manter uma posição por PSP
independente da topologia. Foi rejeitado porque adiciona estado durável e
lifecycle de materialização que o cursor baseado em offset não precisa no MVP.

### Retenção indefinida

Kafka infinito, arquivamento frio ou watermark persistido por PSP aumentariam
custo e complexidade para uma indisponibilidade que pertence a disaster
recovery. Foi rejeitado em favor da janela explícita de sete dias.

## Validação funcional

Os testes devem provar:

- pagamento, auditoria e outbox são atômicos;
- falha do insert da outbox reverte a transação financeira;
- startup drena a outbox antes de iniciar listeners;
- o caminho after-commit publica sem SELECT da outbox;
- fila cheia aplica backpressure e não perde rows;
- somente um publisher executa batches;
- todos os ACKs permitem delete integral;
- falha ou resultado inconclusivo não deleta nenhuma row;
- retry usa mesmos bytes e `communication_id`;
- crash entre ACK e delete produz duplicata, nunca perda;
- falha do worker deixa o SPI unhealthy e interrompe novo consumo;
- chave Kafka é `recipient_ispb`;
- topologia `v1` incompatível é rejeitada;
- cursor é vinculado ao PSP, geração, partição e offset;
- cursor adulterado, cruzado, futuro ou expirado é rejeitado;
- cursor avança pelo último offset examinado, inclusive em resposta vazia;
- limite de 15 notificações e orçamento de scan são respeitados;
- PSPs na mesma partição compartilham uma única leitura no caminho saudável;
- cache miss lê Kafka e não PostgreSQL;
- restart do Gateway preserva entrega pelo Kafka;
- restart do PSP preserva at-least-once pelo cursor;
- duplicatas físicas são entregues com identidade invariável;
- happy path produz ACSC e insufficient funds produz RJCT/AM04.

## Validação de performance

Após testes automatizados e smoke funcional, executar A/B com o mesmo perfil,
recursos, aquecimento e instrumentação do baseline vigente. Comparar, no
mínimo:

- TPS original oferecido e rolling minimum;
- outcomes completos;
- latência end-to-end p50/p95/p99/max;
- CPU e WAL do PostgreSQL;
- tempo SQL de insert e delete da outbox;
- CPU, disco e lag do Kafka;
- tamanho real dos batches do publisher;
- taxa de cache hit e cache miss do Gateway;
- registros Kafka examinados por Pull;
- tamanho dos lotes Pull observados;
- duplicatas físicas;
- saturação e tempo bloqueado na fila do publisher.

O experimento não altera recursos para favorecer o B. O resultado deve ser
registrado mesmo se a mudança piorar performance; a decisão final permanece
do usuário.

## Consequências aceitas

O desenho reduz trabalho relacional e simplifica ownership, mas aceita:

- Kafka como parte da correctness;
- cursor internamente acoplado a topic/partition/offset;
- mudança de partições somente por nova geração;
- filtragem de PSPs que compartilham partição;
- reread histórico em cache miss;
- duplicatas físicas;
- retenção limitada;
- backpressure local sem admission control end-to-end;
- ausência de HA física no MVP;
- uma outbox temporária ainda necessária por não usarmos CDC.

Essas desvantagens são deliberadas. O objetivo não é resolver antecipadamente
retenção infinita, reparticionamento transparente e alta disponibilidade, mas
entregar um protocolo confiável e mensurável sem continuar usando o
PostgreSQL financeiro como log histórico de notificações.
