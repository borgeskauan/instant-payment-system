# Normalizar lotes PACS na entrada do Kafka producer

- [ ] Normalizar lotes PACS na entrada do Kafka producer

**Por que existe**

O `kafka-producer` já reconhece `pacs.008` e `pacs.002` na borda HTTP e publica nos tópicos internos do SPI um registro Kafka por transação/status. O teste de carga `invariant-kafka-event-one-transaction-15m` validou essa invariante no caminho de load test direto para o Kafka producer.

Ainda falta aplicar a mesma padronização no PSP real. O PSP continua carregando o modelo antigo de lote no domínio e nos serviços (`PaymentBatch`, `StatusBatch`, `BatchDetails`, `handleTransferRequestBatch`, `handleStatusBatch`) e ainda precisa ser simplificado para tratar cada notificação PACS recebida como uma unidade lógica: uma transação ou um status por payload.

**Tarefas**

- [x] Fazer o `kafka-producer` reconhecer `pacs.008` e `pacs.002` na borda HTTP.
- [x] Dividir `pacs.008` com múltiplos `CdtTrfTxInf` em um registro Kafka por transação.
- [x] Dividir `pacs.002` com múltiplos `TxInfAndSts` em um registro Kafka por status.
- [x] Publicar nos tópicos internos do SPI protobufs internos com uma única unidade lógica por registro Kafka.
- [x] Atualizar o SPI para consumir os protobufs internos e manter a PACS isolada na borda.
- [x] Atualizar o load tool para preservar PACS na entrada, mas validar o fluxo interno com um evento Kafka por transação/status.
- [x] Medir impacto em throughput e latência no teste `invariant-kafka-event-one-transaction-15m`.
- [ ] Atualizar o cliente gRPC do PSP para o contrato `NotificationBatch` do notification-gateway e iterar todos os payloads recebidos.
- [ ] Remover o modelo de lote do domínio do PSP (`PaymentBatch`, `StatusBatch`, `BatchDetails`).
- [ ] Trocar APIs internas do PSP para processar unidade lógica: `handleTransferRequest(PaymentTransaction)` e `handleStatus(StatusReport)`.
- [ ] Manter PACS apenas na borda de entrada/saída do PSP, separada dos DTOs internos.
- [ ] Fazer o PSP rejeitar ou sinalizar payload PACS recebido com mais de uma transação/status, se esse caso chegar pela notificação.
- [ ] Atualizar testes do PSP para validar a regra: uma notificação PACS recebida = uma transação/status processado.
- [ ] Definir como ajustar ou preservar metadados do `GrpHdr`, como `MsgId`, `NbOfTxs`, timestamps e identificadores de lote original.
- [ ] Adicionar metadados de rastreabilidade: mensagem original, índice do item, tamanho do lote original, ISPB de origem e hash do payload original.
- [ ] Definir comportamento de falha para publicação parcial: falhar a requisição inteira ou registrar compensação/reprocessamento.

**Referências**

- `kafka-producer/`: entrada HTTP que normaliza PACS para protobuf interno.
- `spi/`: consumo dos protobufs internos.
- `payment-service-provider/`: próximo serviço a ser padronizado.
- `load-test/results/invariant-kafka-event-one-transaction-15m`: teste que validou a invariante no caminho de carga.
