# Normalizar lotes PACS na entrada do Kafka producer

- [x] Normalizar lotes PACS na entrada do Kafka producer

**Por que existe**

O `kafka-producer` reconhece `pacs.008` e `pacs.002` na borda HTTP e publica nos tópicos internos do SPI um registro Kafka por transação/status. O teste de carga `invariant-kafka-event-one-transaction-15m` validou essa invariante no caminho de load test direto para o Kafka producer.

O escopo desta atividade foi fechado na normalização da entrada do Kafka producer, no contrato interno com o SPI e na adaptação do cliente gRPC do PSP para consumir `NotificationBatch`. A limpeza restante no PSP foi tratada separadamente ou despriorizada para uma frente própria, porque o PSP ainda preserva o modelo interno antigo de lote e não faz parte do hot path medido pelo load test principal.

**Tarefas**

- [x] Fazer o `kafka-producer` reconhecer `pacs.008` e `pacs.002` na borda HTTP.
- [x] Dividir `pacs.008` com múltiplos `CdtTrfTxInf` em um registro Kafka por transação.
- [x] Dividir `pacs.002` com múltiplos `TxInfAndSts` em um registro Kafka por status.
- [x] Publicar nos tópicos internos do SPI protobufs internos com uma única unidade lógica por registro Kafka.
- [x] Atualizar o SPI para consumir os protobufs internos e manter a PACS isolada na borda.
- [x] Atualizar o load tool para preservar PACS na entrada, mas validar o fluxo interno com um evento Kafka por transação/status.
- [x] Medir impacto em throughput e latência no teste `invariant-kafka-event-one-transaction-15m`.

**Movido ou despriorizado**

- [x] Atualizar o cliente gRPC do PSP para o contrato `NotificationBatch` do notification-gateway e iterar todos os payloads recebidos.
- [ ] Limpeza do modelo antigo de lote no PSP (`PaymentBatch`, `StatusBatch`, `BatchDetails`) movida para uma frente futura de simplificação do PSP.
- [ ] Troca das APIs internas do PSP para processar unidade lógica movida para uma frente futura de simplificação do PSP.
- [ ] Validação de payload PACS recebido com múltiplas transações/status no PSP despriorizada neste escopo.
- [ ] Ajuste/preservação de metadados do `GrpHdr` movido para a frente de auditoria/rastreabilidade.
- [ ] Metadados de rastreabilidade do lote original movidos para a frente de auditoria/rastreabilidade.
- [ ] Comportamento de falha para publicação parcial movido para a frente de reprocessamento/DLQ.

**Referências**

- `kafka-producer/`: entrada HTTP que normaliza PACS para protobuf interno.
- `spi/`: consumo dos protobufs internos.
- `payment-service-provider/`: cliente gRPC atualizado para `NotificationBatch`; limpeza interna de `PaymentBatch`/`StatusBatch` ficou para frente própria.
- `load-test/results/invariant-kafka-event-one-transaction-15m`: teste que validou a invariante no caminho de carga.
