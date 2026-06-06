# Agora

Este arquivo guarda apenas a frente ativa do projeto. A regra é manter uma tarefa principal por vez, com contexto suficiente para retomar o trabalho depois de uma pausa longa.

## Tarefa ativa

- [ ] Reduzir o consumo de CPU do container SPI

## Contexto

O SPI é o serviço central do fluxo Pix simulado. Ele recebe pedidos de transferência, processa mensagens vindas do Kafka, encaminha notificações aos PSPs, processa status reports e executa a liquidação simulada.

A tarefa existe porque, durante a execução em container e/ou testes de carga, o container do SPI apresentou consumo alto de CPU. O problema ainda precisa ser caracterizado: não está claro se a CPU vem do fluxo de processamento, polling, Kafka consumer, logging, serialização, configuração de threads ou limites do container.

## Objetivo

Identificar a causa principal do consumo de CPU do SPI e reduzir esse consumo sem quebrar o fluxo Pix ponta a ponta.

## Como retomar

1. Reproduzir o cenário em que o SPI consome CPU demais.
2. Registrar um baseline antes de alterar código:
   - CPU do container SPI.
   - Memória do container SPI.
   - Throughput do teste.
   - Latência do fluxo.
   - Taxa de erro.
3. Identificar qual parte do fluxo está ativa durante o pico de CPU.
4. Adicionar ou validar métricas com Micrometer se ainda não houver visibilidade suficiente.
5. Aplicar uma otimização pequena por vez.
6. Reexecutar o mesmo teste e comparar com o baseline.

## Hipóteses iniciais

- Long polling ou loops de consulta mantendo CPU ocupada.
- Kafka consumer/processamento sem controle adequado de ritmo.
- Logging excessivo no caminho quente do fluxo Pix.
- Configuração de threads do servidor web ou runtime Java.
- Serialização/conversão de mensagens consumindo CPU demais.
- Limites de CPU do container amplificando o problema.

## Referências locais

- `docs/PIX_FLOW_LOGGING_GUIDE.md`: descreve o fluxo Pix e onde o SPI participa.
- `docs/board/backlog.md`: contém o histórico e pendências antigas do projeto.
- `load-test/`: contém scripts e resultados anteriores de teste de carga.
- `spi/`: implementação do serviço SPI.