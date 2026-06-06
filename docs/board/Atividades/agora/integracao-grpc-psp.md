# Adequar PSP para se integrar com microserviço gRPC

- [ ] Substituir o consumo direto de Kafka no PSP por cliente gRPC para o `notification-gateway`.

**Por que existe**

Hoje o `payment-service-provider` ainda consome notificações diretamente do tópico interno `notifications-topic`. Isso acopla o PSP à infraestrutura interna de Kafka. A arquitetura desejada é fazer o SPI publicar notificações no Kafka, o `notification-gateway` consumir esse tópico internamente e os PSPs receberem notificações por gRPC server-streaming.

**O que já foi feito**

- [x] Criado o microserviço `notification-gateway`.
- [x] Implementado consumo Kafka -> gRPC streaming no `notification-gateway`.
- [x] Criado contrato `notification.proto`.
- [x] Adequado o teste de carga K6 para consumir notificações via gRPC.
- [x] Documentada a decisão em `docs/LOAD_TEST_KAFKA_INTEGRATION.md`.

**O que falta**

- [ ] Criar cliente gRPC no `payment-service-provider`.
- [ ] Reaproveitar a lógica atual de processamento de notificações do PSP.
- [ ] Remover ou desativar o consumer Kafka direto do PSP.
- [ ] Configurar endereço do `notification-gateway` por ambiente.
- [ ] Validar o fluxo Pix ponta a ponta usando PSP real, não apenas K6.

**Referências**

- `docs/LOAD_TEST_KAFKA_INTEGRATION.md`
- `notification-gateway/src/main/proto/notification.proto`
- `payment-service-provider/src/main/java/br/kauan/paymentserviceprovider/adapter/input/kafka/NotificationConsumer.java`
