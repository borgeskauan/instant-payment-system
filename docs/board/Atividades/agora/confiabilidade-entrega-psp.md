# Confiabilidade de entrega para PSPs com transactional outbox

- [ ] Confiabilidade de entrega para PSPs com transactional outbox

Esta frente está priorizada como uma feature arquitetural separada da idempotência/replay atual.

## Reliable PSP Delivery com transactional outbox, ACK, retry e timeout

**Por que existe**

Hoje o SPI processa `pacs.008` e `pacs.002`, altera o estado da transação no banco e publica efeitos laterais para os PSPs, como pedido de aceite, notificação de rejeição e notificação de liquidação. A idempotência atual torna replays seguros, mas não garante sozinha que o sistema nunca esqueça um efeito lateral obrigatório depois de commitar uma alteração no banco.

O objetivo desta frente é persistir explicitamente os efeitos laterais destinados aos PSPs e controlar a entrega por destinatário até confirmação, expiração ou tratamento operacional. O modelo esperado é `at-least-once`: a mesma mensagem pode ser enviada mais de uma vez, e os consumidores precisam permanecer idempotentes.

**Corte inicial**

Antes da implementação completa do worker, fechar o desenho mínimo da entrega confiável:

- [ ] Definir o contrato de confirmação: quando uma delivery pode virar `ACKED`.
- [ ] Definir quais eventos entram no primeiro corte: `ACCEPTANCE_REQUEST`, `REJECTED_NOTIFICATION` e `SETTLED_NOTIFICATION`.
- [ ] Definir a política de timeout para `ACCEPTANCE_REQUEST` pendente.
- [ ] Definir o modelo persistente de `outbox_event` e `outbox_delivery`.
- [ ] Definir a chave de idempotência usada na entrega.

**Tarefas**

- [ ] Definir o contrato de confirmação: o que significa `ACKED` para uma entrega PSP.
- [ ] Definir a política de timeout para `ACCEPTANCE_REQUEST` pendente e o status final de negócio após expiração.
- [ ] Criar modelo persistente de outbox, com evento e delivery por destinatário.
- [ ] Registrar outbox na mesma transação de banco que cria ou altera o estado da transação.
- [ ] Gerar deliveries para `ACCEPTANCE_REQUEST`, `REJECTED_NOTIFICATION` e `SETTLED_NOTIFICATION`.
- [ ] Avaliar se `STATUS_NOTIFICATION_REPLAY`, DLQ ou anomalias devem usar outbox como efeito lateral obrigatório.
- [ ] Implementar worker de delivery com seleção concorrente segura, retry e backoff configurável.
- [ ] Controlar estados equivalentes a `PENDING`, `IN_FLIGHT`, `ACKED`, `RETRYABLE_FAILED`, `EXPIRED` e `DEAD`.
- [ ] Garantir chave de idempotência por delivery, preferencialmente persistida ou baseada em `event_id`.
- [ ] Permitir confirmação independente por PSP destinatário.
- [ ] Reconciliar retries/replays de `pacs.008` e `pacs.002` com outbox existente, sem criar eventos duplicados indevidos.
- [ ] Adicionar métricas de deliveries pendentes, retries, falhas, expirados, mortos, tempo até ACK e lag do worker.
- [ ] Cobrir falhas simuladas: crash após commit, rollback, PSP indisponível, retry, duplicata, ACK perdido e expiração.

**Fora de escopo inicial**

- [ ] Garantia exactly-once fim-a-fim.
- [ ] Transação distribuída entre banco, Kafka e PSP.
- [ ] Reescrever todo o fluxo de pagamentos.
- [ ] Remover a idempotência existente.
- [ ] Resolver reconciliação manual completa.

**Notas**

- Estado da transação continua sendo a verdade operacional.
- Outbox passa a ser a lista explícita de efeitos laterais obrigatórios.
- Delivery tracking controla envio, retry, ACK e expiração por PSP.
- Publicar no Kafka não deve ser tratado automaticamente como ACK fim-a-fim do PSP, salvo se esse contrato for definido explicitamente.
