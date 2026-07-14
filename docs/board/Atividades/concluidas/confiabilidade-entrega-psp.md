# Confiabilidade de entrega para PSPs

- [x] Primeiro corte de confiabilidade de entrega para PSPs

Esta frente está priorizada como uma feature arquitetural separada da idempotência/replay atual.

## Reliable PSP Delivery com ACK, retry e lease

**Por que existe**

Hoje o SPI processa `pacs.008` e `pacs.002`, altera o estado da transação no banco e publica efeitos laterais para os PSPs, como pedido de aceite, notificação de rejeição e notificação de liquidação. A idempotência atual torna replays seguros, mas não garante sozinha que o PSP destinatário observou e processou a notificação.

O primeiro corte persiste as notificações destinadas aos PSPs no `notification-gateway`, controla a entrega por destinatário e marca a delivery como concluída somente após ACK explícito do PSP. O modelo é `at-least-once`: a mesma mensagem pode ser enviada mais de uma vez, e os consumidores precisam permanecer idempotentes.

**Decisões do corte atual**

- O SPI continua publicando notificações no Kafka interno `psp-notifications`.
- Cada notificação tem `notification.communication-id` determinístico.
- O `notification-gateway` consome o Kafka e faz upsert em `notification_delivery`.
- `ACKED` significa que o PSP recebeu a notificação pelo stream gRPC, processou com sucesso e enviou `Ack`.
- Publicar no Kafka não é ACK fim-a-fim do PSP.
- O stream gRPC é bidirecional: primeira mensagem do PSP é `Subscribe`, depois o PSP envia `Ack` por delivery processada.
- `IN_FLIGHT` é uma lease, não um lock aberto durante o envio.
- Se não houver ACK, a delivery volta a ser elegível quando `lease_until` vence.
- Não existe NACK no v1: falha de processamento no PSP significa ausência de ACK.
- O worker busca apenas deliveries de ISPBs conectados localmente à instância do gateway.

**Corte entregue**

- [x] Definir o contrato de confirmação: quando uma delivery pode virar `ACKED`.
- [x] Definir quais eventos entram no primeiro corte: `ACCEPTANCE_REQUEST`, `REJECTED_NOTIFICATION` e `SETTLED_NOTIFICATION`.
- [x] Criar modelo persistente de delivery por destinatário no `notification-gateway`.
- [x] Persistir deliveries consumidas de `psp-notifications`.
- [x] Implementar worker de delivery com seleção concorrente segura, retry e lease.
- [x] Permitir confirmação independente por PSP destinatário.
- [x] Garantir chave de idempotência por delivery via `communication_id`.
- [x] Reconciliar replay com delivery existente, sem criar duplicata indevida para o mesmo `communication_id`.
- [x] Testar manualmente PSP offline: delivery fica `PENDING` e vira `ACKED` quando o PSP reconecta.
- [x] Testar manualmente PSP online: delivery é entregue e vira `ACKED`.
- [x] Testar manualmente restart do `notification-gateway`: delivery persistida não se perde e é entregue após reconexão.
- [x] Testar manualmente replay/idempotência: reenvio não cria delivery duplicada indevida para o mesmo `communication_id`.

**Pendências futuras**

- [ ] Adicionar métricas de deliveries pendentes, retries, falhas, expirados, mortos, tempo até ACK e lag do worker.
- [ ] Definir política de timeout de negócio para `ACCEPTANCE_REQUEST` pendente e status final após expiração.
- [ ] Adicionar estados/políticas operacionais para `EXPIRED` e `DEAD`.
- [ ] Configurar backoff e limite de tentativas por tipo de evento.
- [ ] Avaliar se DLQ/anomalias devem ter delivery tracking como efeito lateral obrigatório.
- [ ] Cobrir com testes automatizados no load-tool: PSP indisponível, retry, duplicata, ACK perdido, restart do gateway e expiração.

**Fora de escopo inicial**

- [ ] Garantia exactly-once fim-a-fim.
- [ ] Transação distribuída entre banco, Kafka e PSP.
- [ ] Transactional outbox dentro do SPI.
- [ ] Reescrever todo o fluxo de pagamentos.
- [ ] Remover a idempotência existente.
- [ ] Resolver reconciliação manual completa.

**Notas**

- Estado da transação continua sendo a verdade operacional.
- O `notification-gateway` passa a ser o ponto de delivery tracking para PSPs.
- Delivery tracking controla envio, retry, ACK e lease por PSP.
- Publicar no Kafka não deve ser tratado automaticamente como ACK fim-a-fim do PSP, salvo se esse contrato for definido explicitamente.
