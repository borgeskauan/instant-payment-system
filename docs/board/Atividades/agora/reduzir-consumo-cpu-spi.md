# Reduzir o consumo de CPU do container SPI

- [ ] Reduzir o consumo de CPU do container SPI

**Por que existe**

O SPI é o serviço central do fluxo Pix simulado. Ele recebe pedidos de transferência, processa mensagens vindas do Kafka, encaminha notificações aos PSPs, processa status reports e executa a liquidação simulada.

A tarefa existe porque, durante a execução em container e/ou testes de carga, o container do SPI apresentou consumo alto de CPU. O problema ainda precisa ser caracterizado: não está claro se a CPU vem do fluxo de processamento, polling, Kafka consumer, logging, serialização, configuração de threads ou limites do container.

O objetivo é identificar a causa principal do consumo de CPU do SPI e reduzir esse consumo sem quebrar o fluxo Pix ponta a ponta.

**O que já foi feito**

- [x] Criado fluxo Pix simulado com SPI, PSPs e Kafka.
- [x] Criados testes de carga com K6.
- [x] Implementado limite de recursos no teste de load balancer: CPU e RAM.
- [x] Registrados resultados anteriores em `load-test/summary/`.
- [x] Documentado o fluxo Pix em `docs/PIX_FLOW_LOGGING_GUIDE.md`.

**O que falta**

- [ ] Reproduzir o cenário em que o SPI consome CPU demais.
- [ ] Registrar baseline antes de alterar código:
  - [ ] CPU do container SPI.
  - [ ] Memória do container SPI.
  - [ ] Throughput do teste.
  - [ ] Latência do fluxo.
  - [ ] Taxa de erro.
- [ ] Identificar qual parte do fluxo está ativa durante o pico de CPU.
- [ ] Adicionar ou validar métricas com Micrometer se ainda não houver visibilidade suficiente.
- [ ] Investigar hipóteses iniciais:
  - [ ] Long polling ou loops de consulta mantendo CPU ocupada.
  - [ ] Kafka consumer/processamento sem controle adequado de ritmo.
  - [ ] Logging excessivo no caminho quente do fluxo Pix.
  - [ ] Configuração de threads do servidor web ou runtime Java.
  - [ ] Serialização/conversão de mensagens consumindo CPU demais.
  - [ ] Limites de CPU do container amplificando o problema.
- [ ] Aplicar uma otimização pequena por vez.
- [ ] Reexecutar o mesmo teste e comparar com o baseline.

**Referências**

- `docs/PIX_FLOW_LOGGING_GUIDE.md`: descreve o fluxo Pix e onde o SPI participa.
- `docs/board/Backlog.md`: contém o histórico e pendências antigas do projeto.
- `load-test/`: contém scripts e resultados anteriores de teste de carga.
- `spi/`: implementação do serviço SPI.
