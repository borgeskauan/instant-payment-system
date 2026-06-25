# Reduzir o consumo de CPU do container SPI

- [x] Reduzir o consumo de CPU do container SPI

**Por que existe**

O SPI é o serviço central do fluxo Pix simulado. Ele recebe pedidos de transferência, processa mensagens vindas do Kafka, encaminha notificações aos PSPs, processa status reports e executa a liquidação simulada.

A tarefa existe porque, durante a execução em container e/ou testes de carga, o container do SPI apresentou consumo alto de CPU. O problema ainda precisa ser caracterizado: não está claro se a CPU vem do fluxo de processamento, polling, Kafka consumer, logging, serialização, configuração de threads ou limites do container.

O objetivo é identificar a causa principal do consumo de CPU do SPI e reduzir esse consumo sem quebrar o fluxo Pix ponta a ponta.

**O que já foi feito**

- [x] Criado fluxo Pix simulado com SPI, PSPs e Kafka.
- [x] Criados testes de carga com K6.
- [x] Implementado limite de recursos no teste de load balancer: CPU e RAM.
- [x] Registrados resultados anteriores em `load-test/results/`.
- [x] Documentado o fluxo Pix em `docs/PIX_FLOW_LOGGING_GUIDE.md`.

**O que foi concluído**

- [x] Reproduzido o cenário em que o SPI consumia CPU demais.
- [x] Registrado baseline antes das alterações:
  - [x] CPU do container SPI.
  - [x] Memória do container SPI.
  - [x] Throughput do teste.
  - [x] Latência do fluxo.
  - [x] Taxa de erro.
- [x] Identificada a participação de Kafka, SPI, PostgreSQL e notification-gateway no caminho quente.
- [x] Analisados traces, JFRs e resultados em `load-test/results/`.
- [x] Aplicadas otimizações incrementais no SPI e no fluxo Kafka/SPI.
- [x] Reexecutados testes de carga e comparados com os baselines.
- [x] Validado o novo fluxo com o teste `invariant-kafka-event-one-transaction-15m`.

**Referências**

- `docs/PIX_FLOW_LOGGING_GUIDE.md`: descreve o fluxo Pix e onde o SPI participa.
- `docs/board/Backlog.md`: contém o histórico e pendências antigas do projeto.
- `load-test/`: contém scripts e resultados anteriores de teste de carga.
- `spi/`: implementação do serviço SPI.
