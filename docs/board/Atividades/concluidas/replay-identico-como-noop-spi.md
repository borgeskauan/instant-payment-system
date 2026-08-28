# Replay idêntico como no-op no SPI

- [x] Garantir que replays idênticos não reconstruam efeitos financeiros ou obrigações de saída

## Estado

Concluída. A semântica já está presente no SPI vigente e foi confirmada durante o encerramento do cleanup.

## Contrato entregue

* replay idêntico de PACS.008 não cria pagamento, reserva, auditoria ou obrigação de notificação adicional;
* replay idêntico de PACS.008 não recria uma obrigação removida artificialmente da outbox;
* replay idêntico de PACS.002 não reaplica transição, crédito, liberação, settlement, auditoria ou notificação;
* duplicata PACS.008 divergente e status report contraditório continuam sendo conflitos explícitos;
* cada transição de negócio cria no máximo uma obrigação lógica por destinatário;
* replays permanecem no workload como carga adicional, sem substituir pagamentos originais.

Replay de entrada não é mecanismo de reparo da saída. A produção atômica da obrigação e sua publicação pertencem ao pipeline de notificações; a entrega física `at-least-once` ao PSP usa o log durável Kafka e o cursor reapresentado no protocolo Pull. O Gateway vigente não usa ACK individual, lease ou persistência de delivery por notificação.

## Evidências

* `JdbcPaymentTransactionRepositoryIntegrationTest#identicalPaymentReplayDoesNotReserveTwice` protege pagamento, reserva e acceptance request únicos;
* `TransactionalOutboxIntegrationTest#waitingAcceptanceReplayIsANoOpEvenWhenTheOriginalOutboxRowIsMissing` prova que replay não reconstrói outbox;
* `StatusTransitionPolicyTest` e os testes de integração de status protegem no-op terminal, crédito ou liberação únicos e rejeição de contradições;
* o smoke final executou e aceitou 65 replays PACS.008 e 51 replays PACS.002, sem violações, outcomes ausentes ou contraditórios.

## Limitação vigente

Corrupção ou remoção operacional da obrigação de saída exige recuperação própria; repetir a entrada não tenta reconstruí-la. A entrega física continua `at-least-once`, portanto o PSP permanece responsável pela idempotência ao reapresentar um cursor antigo.
