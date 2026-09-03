# Como o sistema reage a falhas

Este documento responde a uma pergunta:

> Quando algo não pode ser processado, devemos rejeitar, tentar novamente, separar a mensagem ou concluir normalmente?

Aqui o foco é a reação operacional. As regras que protegem o dinheiro estão em [Como o pagamento permanece correto](payment-correctness.md), e as falhas específicas da entrega estão em [Como uma confirmação continua recuperável](notification-delivery.md).

## Primeiro: tentar novamente poderia funcionar?

Nem todo resultado negativo é uma falha técnica:

| Situação | Classificação | Reação |
| --- | --- | --- |
| saldo insuficiente | resultado de negócio | persiste `REJECTED` e notifica o pagador |
| recebedor rejeitou | resultado de negócio | devolve a reserva, persiste `REJECTED` e notifica |
| mesma mensagem reapareceu | repetição idempotente | não produz novo efeito |
| mensagem externa não pode ser convertida | entrada inválida na borda HTTP | responde `400`; não publica no Kafka |
| identidade mTLS não pode ser obtida | autenticação inválida | responde `401`; não publica no Kafka |
| mensagem Kafka viola o contrato interno | entrada inválida no Payment Processor | isola a mensagem na DLQ |
| instituição não tem autoridade para a operação | violação de autorização | não altera o negócio e envia a mensagem à DLQ |
| banco está temporariamente indisponível | falha de infraestrutura | mantém o lote pendente e tenta novamente |
| código falha sem classificação segura | defeito interno | tenta novamente e depois isola o lote na DLQ |

A decisão central é esta:

```text
o mesmo trabalho pode funcionar depois?
        │
        ├── sim, infraestrutura indisponível → preservar e repetir
        │
        └── não com a mesma entrada
                ├── mensagem identificável → isolar essa mensagem
                └── origem incerta no lote → tratar o lote inteiro
```

Uma **dead-letter queue (DLQ)** não representa rejeição de negócio nem serve para guardar trabalho durante uma indisponibilidade temporária. Ela separa uma mensagem que continuaria falhando com a mesma entrada e impediria as mensagens seguintes de avançar.

## A resposta HTTP fala apenas sobre a entrada

O Payment Ingress autentica a instituição pelo certificado mTLS, converte a mensagem externa para o formato interno e espera o Kafka confirmar a publicação antes de responder.

| Resposta | Significado |
| --- | --- |
| `200` | todas as mensagens produzidas a partir da entrada foram confirmadas pelo Kafka |
| `400` | a mensagem de pagamento externa (PACS) não possui a estrutura mínima ou os valores necessários |
| `401` | a identidade da instituição não pôde ser extraída do certificado |
| `500` | publicação Kafka ou processamento interno da borda falhou |

`200` não significa que o Payment Processor aceitou ou concluiu o pagamento. Significa apenas que a entrada foi autenticada, convertida e publicada.

O Ingress não decide se a instituição autenticada é a pagadora correta nem se uma resposta veio do recebedor correto. Essas regras dependem do estado do pagamento e permanecem no Payment Processor.

### E se apenas parte da mensagem chegar ao Kafka?

Uma mensagem externa pode produzir vários registros no Kafka. Se apenas parte deles for confirmada, a resposta HTTP é `500`.

O cliente pode então repetir a mensagem inteira, inclusive a parte que já chegou. O Payment Processor absorve essas repetições por idempotência; o Ingress não tenta criar uma transação distribuída entre várias publicações.

## Uma mensagem inválida não precisa condenar o lote inteiro

Os consumidores recebem grupos de mensagens, mas preservam a relação entre cada comando e seu registro de origem no Kafka.

Antes do processamento financeiro, cada mensagem é verificada individualmente:

* presença e formato único da identidade autenticada em `authenticated-ispb`;
* conteúdo não vazio;
* mensagem interna decodificável;
* campos mínimos do contrato interno;
* moeda, valor, ISPB e tipos suportados.

Um registro inválido segue para a DLQ, enquanto os demais registros válidos do mesmo lote continuam.

Depois da classificação do pagamento, o Payment Processor também consegue identificar individualmente:

* identidade reutilizada com conteúdo divergente;
* decisão incompatível com o estado do pagamento;
* pagamento inexistente para a decisão recebida;
* tentativa feita por uma instituição sem autoridade.

Esses registros não produzem efeito financeiro e seguem para a DLQ correspondente. Uma repetição equivalente apenas preserva o resultado existente e não vai para a DLQ.

O lote só é marcado como processado no Kafka depois que:

1. as mensagens válidas concluíram sua transação;
2. as mensagens inválidas foram confirmadas na DLQ;
3. o lote inteiro chegou a uma conclusão conhecida.

Se a publicação na DLQ falhar, o lote continua pendente. O publicador espera a confirmação do Kafka por até dez segundos e propaga a falha em vez de avançar silenciosamente.

## A DLQ guarda contexto, mas não corrige nada

Cada tópico de entrada possui uma DLQ na mesma partição lógica:

```text
spi-payment-requests
→ spi-payment-requests.dlq

spi-payment-status-reports
→ spi-payment-status-reports.dlq
```

A mensagem mantém chave e conteúdo originais. Metadados adicionais registram:

* tópico, partição, posição e instante de origem;
* grupo consumidor;
* serviço que classificou a falha;
* tipo de erro;
* classe, mensagem e resumo da pilha de erro;
* instante da classificação.

Os tipos atuais distinguem `INVALID_PAYLOAD`, `DIVERGENT_DUPLICATE`, `STATUS_REPORT_CONFLICT`, `NOT_AUTHENTICATED`, `UNAUTHORIZED_PSP` e `BATCH_PROCESSING_ERROR`.

A DLQ não reprocessa mensagens automaticamente. Reintroduzir uma mensagem exige uma decisão operacional, porque repeti-la sem corrigir a causa apenas recriaria o mesmo problema.

## Indisponibilidade temporária preserva o trabalho

Quando PostgreSQL produz uma falha claramente transitória, o Payment Processor a classifica como infraestrutura indisponível.

O consumidor então:

```text
não avança sua posição no Kafka
        ↓
pausa o container por 30 s
        ↓
tenta o mesmo lote novamente
        ↓
repete enquanto a infraestrutura continuar indisponível
```

Não há limite de tentativas para essa classe. A mensagem continua válida; movê-la para a DLQ perderia trabalho que pode voltar a ser processável sem nenhuma alteração.

A transação PostgreSQL é revertida antes da nova tentativa. Estado, saldo, auditoria e outbox não ficam parcialmente aplicados.

## Uma falha sem origem conhecida afeta o lote

Uma exceção inesperada pode acontecer em um ponto no qual o consumidor não consegue atribuir a causa com segurança a uma única mensagem.

Nessa situação, o consumidor tenta novamente duas vezes, com intervalo de um segundo. Se o defeito permanecer, todas as mensagens do lote seguem individualmente para a DLQ como `BATCH_PROCESSING_ERROR`, e o lote deixa de bloquear a partição.

Isso evita que um lote problemático bloqueie a partição indefinidamente. O custo é deliberado: mensagens que seriam válidas isoladamente também podem ir para a DLQ quando não existe uma forma segura de identificar a origem da falha.

O sistema não tenta adivinhar qual mensagem causou uma exceção arbitrária nem executa parcialmente o lote uma segunda vez fora da transação para descobrir isso.

## Depois do pagamento, falhar ao notificar não muda o resultado

Depois do commit, falhar ao publicar uma confirmação não pode transformar uma transação concluída em uma falsa rejeição.

A outbox já persistida continua sendo a fonte de verdade. O publicador mantém e repete o lote; registros remanescentes também são publicados antes de novos pagamentos serem consumidos na próxima inicialização.

Uma confirmação Kafka parcial ou um delete inconclusivo pode repetir o lote completo. Essa fronteira prefere duplicata recuperável a perda silenciosa.

O limite atual é que uma obrigação que não chegou ao publicador logo depois do commit não é procurada periodicamente durante a mesma execução. Esse caso depende de uma reinicialização e está detalhado em [Como uma confirmação continua recuperável](notification-delivery.md).

## No Pull, o erro diz ao cliente o que fazer

O Notification Gateway não move uma notificação para DLQ quando um Pull falha. Ele devolve um status que permite ao cliente distinguir a reação:

| Situação | Status gRPC | Reação esperada |
| --- | --- | --- |
| cursor adulterado, de outra instituição ou com posição impossível | `INVALID_ARGUMENT` | corrigir estado local; repetir sem mudança não ajuda |
| cursor expirou pela retenção | `FAILED_PRECONDITION` | sair do fluxo operacional e iniciar recuperação |
| segundo Pull concorrente da mesma instituição | `FAILED_PRECONDITION` | manter apenas um fluxo lógico |
| Pull interrompido no servidor | `UNAVAILABLE` | repetir usando o último cursor durável |
| Pull chega ao timeout sem dados | resposta vazia | emitir novo Pull com o mesmo cursor |

O Gateway nunca afirma por conta própria que a instituição processou um lote. Se a conexão cair ou a resposta se perder, o cliente reapresenta seu último cursor durável e pode receber mensagens repetidas.

## Quem toma cada decisão

| Falha | Quem classifica | Estado preservado | Saída |
| --- | --- | --- | --- |
| PACS não convertível | Payment Ingress | nenhuma mensagem publicada | HTTP `400` |
| certificado sem identidade válida | Payment Ingress | nenhuma mensagem publicada | HTTP `401` |
| Kafka não confirma o ingresso | Payment Ingress | cliente ainda possui a mensagem original | HTTP `500` |
| mensagem interna inválida ou sem autenticação | Payment Processor | conteúdo original e origem Kafka | DLQ do tópico |
| conflito ou falta de autoridade | Payment Processor | estado financeiro inalterado | DLQ do tópico |
| repetição equivalente | Payment Processor | resultado original | nenhum novo efeito e confirmação normal |
| rejeição financeira válida | domínio do Payment Processor | fato `REJECTED` e notificação | resultado de negócio |
| PostgreSQL indisponível | infraestrutura do Payment Processor | posição e lote não confirmados | pausa e novas tentativas |
| defeito interno persistente | tratamento de erros do Payment Processor | mensagens originais | lote para DLQ após tentativas curtas |
| publicação da confirmação inconclusiva | outbox do Payment Processor | obrigação no PostgreSQL | repete o lote |
| cursor inválido ou expirado | Notification Gateway | progresso continua com a instituição | erro gRPC explícito |

## O que ainda não está coberto

O tratamento atual não inclui:

* reprocessamento automático ou interface operacional para DLQs;
* métricas, dashboards e alertas operacionais das DLQs;
* classificação individual de uma exceção arbitrária surgida no processamento em lote;
* limite de tempo para a indisponibilidade da infraestrutura;
* redução automática da admissão de pagamentos quando a entrega de notificações está indisponível;
* recuperação, durante a mesma execução, de uma obrigação que perdeu o encaminhamento pós-commit.

Esses limites mantêm a regra principal simples: **mensagens que continuarão falhando sem mudança deixam o caminho principal; infraestrutura temporariamente indisponível preserva o trabalho; resultados de negócio continuam sendo resultados, não falhas técnicas**.

## Verificar no código

A borda HTTP pode ser inspecionada em:

* [`ReactorNettyPaymentServer`](../../kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServer.java);
* [`KafkaPaymentPublisher`](../../kafka-producer/src/main/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisher.java);
* [`ReactorNettyPaymentServerTest`](../../kafka-producer/src/test/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServerTest.java).

A classificação do Payment Processor e as DLQs estão em:

* [`PaymentMessageConsumer`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumer.java);
* [`KafkaErrorHandlingConfig`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/error/KafkaErrorHandlingConfig.java);
* [`KafkaDlqConfig`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfig.java);
* [`PaymentMessageConsumerTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumerTest.java);
* [`KafkaErrorHandlingConfigTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/infrastructure/error/KafkaErrorHandlingConfigTest.java);
* [`KafkaDlqConfigTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfigTest.java).

As respostas do protocolo de entrega estão em [`NotificationGrpcService`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java) e [`NotificationGrpcServiceTest`](../../notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/NotificationGrpcServiceTest.java).
