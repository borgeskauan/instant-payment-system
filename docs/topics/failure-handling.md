# Tratamento de falhas

Este documento responde a uma pergunta: **quando uma etapa não consegue processar uma entrada, quem decide entre rejeitar, repetir, isolar ou concluir?**

Seu foco é a condução operacional da falha. As invariantes financeiras permanecem em [Corretude do pagamento](payment-correctness.md), e as falhas específicas do handoff e do Pull estão detalhadas em [Entrega recuperável de notificações](notification-delivery.md).

## A classificação vem antes da reação

Nem todo resultado negativo é uma falha técnica:

| Situação | Classificação | Reação |
| --- | --- | --- |
| saldo insuficiente | resultado de negócio | persiste `REJECTED` e notifica o pagador |
| recebedor rejeitou | resultado de negócio | devolve a reserva, persiste `REJECTED` e notifica |
| mesma mensagem reapareceu | repetição idempotente | não produz novo efeito |
| payload externo não pode ser convertido | entrada inválida na borda HTTP | responde `400`; não publica no Kafka |
| identidade mTLS não pode ser obtida | autenticação inválida | responde `401`; não publica no Kafka |
| record Kafka viola o contrato interno | entrada inválida no SPI | isola o record na DLQ |
| PSP não tem autoridade para a operação | violação determinística de autorização | não altera o negócio e envia o record à DLQ |
| banco está temporariamente indisponível | falha de infraestrutura | mantém o batch pendente e tenta novamente |
| código falha sem classificação segura | defeito interno | retry curto; depois isola o batch na DLQ |

A diferença central é esta:

```text
o mesmo trabalho pode funcionar depois?
        │
        ├── sim, infraestrutura indisponível → preservar e repetir
        │
        └── não com a mesma entrada
                ├── record identificável → isolar o record
                └── origem incerta no batch → tratar o batch inteiro
```

DLQ não representa uma rejeição de negócio e não é armazenamento para indisponibilidade temporária. Ela retira do caminho principal uma entrada que não deve continuar bloqueando o consumer group sem intervenção.

## A borda HTTP só afirma o que conhece

O Payment Ingress autentica a instituição pelo certificado mTLS, converte o envelope PACS para o protobuf interno e espera a confirmação da publicação Kafka antes de responder.

| Resposta | Significado |
| --- | --- |
| `200` | todos os records derivados do envelope foram confirmados pelo producer Kafka |
| `400` | o PACS não possui a estrutura mínima ou os valores necessários para conversão |
| `401` | a identidade da instituição não pôde ser extraída do certificado |
| `500` | publicação Kafka ou processamento interno da borda falhou |

`200` não significa que o SPI admitiu ou concluiu o pagamento. Significa somente que a borda autenticou, converteu e publicou a entrada.

O Ingress não decide se o PSP autenticado é o pagador correto nem se uma resposta veio do recebedor correto. Essas regras dependem do contrato e do estado do pagamento e permanecem no SPI.

### Publicação parcial de um envelope

Um envelope pode produzir vários records Kafka. Se parte deles for confirmada e outra parte falhar, a resposta HTTP é `500`.

O cliente pode repetir o envelope inteiro. Records já publicados podem, portanto, aparecer novamente. O SPI absorve as repetições equivalentes por idempotência; a borda não tenta implementar uma transação distribuída entre os sends.

## O SPI separa falhas por record quando consegue

Os consumers recebem batches, mas preservam a relação entre cada comando decodificado e seu record de origem.

Antes do processamento financeiro, cada record é verificado individualmente:

* presença e formato único do header `authenticated-ispb`;
* payload não vazio;
* protobuf decodificável;
* campos mínimos do contrato interno;
* moeda, valor, ISPB e tipos suportados.

Um record inválido vai para a DLQ e os demais records válidos do mesmo batch continuam.

Depois da classificação transacional, o SPI também consegue atribuir individualmente:

* identidade reutilizada com conteúdo divergente;
* decisão incompatível com o estado do pagamento;
* pagamento inexistente para a decisão recebida;
* tentativa feita por uma instituição sem autoridade.

Esses records não produzem efeito financeiro e seguem para a DLQ correspondente. Uma repetição equivalente, por outro lado, é um `no-op` normal e não vai para a DLQ.

O offset do batch só é confirmado depois que:

1. os records válidos concluíram sua transação;
2. os records determinísticos rejeitados foram confirmados na DLQ;
3. a chamada de acknowledgment chegou ao fim do batch.

Se a publicação na DLQ falhar, a recuperação não é considerada concluída. O producer da DLQ usa `acks=all`, espera o resultado por até dez segundos e propaga a falha em vez de confirmar silenciosamente o offset.

## A DLQ preserva contexto, não corrige a mensagem

Cada tópico de entrada possui uma DLQ na mesma partição lógica:

```text
spi-payment-requests
→ spi-payment-requests.dlq

spi-payment-status-reports
→ spi-payment-status-reports.dlq
```

O record mantém chave e payload originais. Headers adicionais registram:

* tópico, partição, offset e timestamp de origem;
* consumer group;
* serviço que classificou a falha;
* tipo de erro;
* classe, mensagem e stack trace resumida;
* instante da classificação.

Os tipos atuais distinguem `INVALID_PAYLOAD`, `DIVERGENT_DUPLICATE`, `STATUS_REPORT_CONFLICT`, `NOT_AUTHENTICATED`, `UNAUTHORIZED_PSP` e `BATCH_PROCESSING_ERROR`.

A DLQ não possui replay automático. Reintroduzir uma mensagem exige uma decisão operacional consciente, porque repetir sem corrigir a causa apenas recriaria o mesmo problema.

## Infraestrutura indisponível não vai para a DLQ

Quando PostgreSQL produz uma falha claramente transitória ou de acesso ao recurso, o SPI a converte em `InfrastructureUnavailableException`.

O consumer então:

```text
não confirma o offset
        ↓
pausa o container por 30 s
        ↓
tenta o mesmo batch novamente
        ↓
repete enquanto a infraestrutura continuar indisponível
```

Não há limite de tentativas para essa classe. Uma indisponibilidade não torna a mensagem inválida, portanto movê-la para a DLQ perderia trabalho que pode voltar a ser processável sem nenhuma alteração no payload.

A transação PostgreSQL é revertida antes da nova tentativa. Estado, saldo, auditoria e outbox não ficam parcialmente aplicados.

## Defeito interno é uma falha do batch

Uma exceção inesperada pode acontecer em um ponto no qual o consumer não consegue atribuir com segurança a causa a um único record.

Nessa situação, o error handler faz até duas retentativas com intervalo de um segundo. Se o defeito permanecer, todos os records do batch são publicados individualmente na DLQ com `BATCH_PROCESSING_ERROR`, e o offset recuperado é confirmado.

Isso evita um poison batch bloqueando a partição indefinidamente. O custo é deliberado: records que seriam válidos isoladamente também podem ir para a DLQ quando a falha não oferece uma atribuição confiável.

O sistema não tenta adivinhar qual record causou uma exceção arbitrária nem adiciona uma segunda execução parcial fora da transação para descobrir isso.

## Falhas depois do commit financeiro

Depois do commit, falhar ao publicar uma confirmação não pode transformar uma transação concluída em uma falsa rejeição.

A outbox já persistida continua sendo a autoridade. O publisher retém e repete o lote em memória; rows remanescentes também são drenadas antes dos consumers iniciarem no próximo startup.

Uma confirmação Kafka parcial ou um delete inconclusivo pode repetir o lote completo. Essa fronteira prefere duplicata recuperável a perda silenciosa.

O limite vigente é que uma row que não entrou no fast path pós-commit não é redescoberta periodicamente durante o mesmo runtime. Esse caso depende de restart e está explicitado no contrato de [entrega de notificações](notification-delivery.md).

## Falhas no Pull são respostas de protocolo

O Notification Gateway não move uma notificação para DLQ quando um Pull falha. Ele devolve um status que permite ao cliente distinguir a reação:

| Situação | Status gRPC | Reação esperada |
| --- | --- | --- |
| cursor adulterado, de outro PSP ou com offset impossível | `INVALID_ARGUMENT` | corrigir estado local; retry idêntico não ajuda |
| cursor expirou pela retenção | `FAILED_PRECONDITION` | sair do fluxo operacional e iniciar recuperação |
| segundo Pull concorrente do mesmo PSP | `FAILED_PRECONDITION` | manter apenas um fluxo lógico |
| Pull interrompido no servidor | `UNAVAILABLE` | repetir usando o último cursor durável |
| long poll sem dados | resposta vazia | emitir novo Pull com o mesmo cursor |

O Gateway nunca persiste que a instituição processou um lote. Uma conexão quebrada ou uma resposta perdida apenas faz o cliente reapresentar seu último cursor durável, podendo receber duplicatas.

## Matriz de autoridade

| Falha | Quem classifica | Estado preservado | Saída |
| --- | --- | --- | --- |
| PACS não convertível | Payment Ingress | nenhum record publicado | HTTP `400` |
| certificado sem identidade válida | Payment Ingress | nenhum record publicado | HTTP `401` |
| Kafka não confirma o ingresso | Payment Ingress | cliente ainda possui o envelope | HTTP `500` |
| record interno inválido ou sem autenticação | SPI | payload original e origem Kafka | DLQ do tópico |
| conflito ou falta de autoridade | SPI | estado financeiro inalterado | DLQ do tópico |
| repetição equivalente | SPI | resultado original | `no-op` e ack normal |
| rejeição financeira válida | domínio do SPI | fato `REJECTED` e notificação | outcome de negócio |
| PostgreSQL indisponível | infraestrutura do SPI | offset e batch não confirmados | pause e retry ilimitado |
| defeito interno persistente | error handler do SPI | records originais | batch para DLQ após retry curto |
| publicação da confirmação inconclusiva | outbox do SPI | obrigação no PostgreSQL | repete o lote |
| cursor inválido ou expirado | Notification Gateway | progresso continua com o PSP | erro gRPC explícito |

## Limites assumidos

O tratamento atual não inclui:

* replay automático ou interface operacional para DLQs;
* métricas, dashboards e alertas operacionais das DLQs;
* classificação individual de uma exceção arbitrária surgida no processamento bulk;
* limite de tempo para a indisponibilidade da infraestrutura;
* backpressure do transporte de notificações sobre a admissão de pagamentos;
* recuperação em runtime da outbox que perdeu o fast path pós-commit.

Esses limites mantêm a regra principal simples: **entradas determinísticas defeituosas deixam o caminho principal; infraestrutura transitória preserva o trabalho; resultados de negócio continuam sendo resultados, não falhas técnicas**.

## Verificação no repositório

A borda HTTP pode ser inspecionada em:

* [`ReactorNettyPaymentServer`](../../kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServer.java);
* [`KafkaPaymentPublisher`](../../kafka-producer/src/main/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisher.java);
* [`ReactorNettyPaymentServerTest`](../../kafka-producer/src/test/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServerTest.java).

A classificação do SPI e as DLQs estão em:

* [`PaymentMessageConsumer`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumer.java);
* [`KafkaErrorHandlingConfig`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/error/KafkaErrorHandlingConfig.java);
* [`KafkaDlqConfig`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfig.java);
* [`PaymentMessageConsumerTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumerTest.java);
* [`KafkaErrorHandlingConfigTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/infrastructure/error/KafkaErrorHandlingConfigTest.java);
* [`KafkaDlqConfigTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfigTest.java).

As respostas do protocolo de entrega estão em [`NotificationGrpcService`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java) e [`NotificationGrpcServiceTest`](../../notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/NotificationGrpcServiceTest.java).
