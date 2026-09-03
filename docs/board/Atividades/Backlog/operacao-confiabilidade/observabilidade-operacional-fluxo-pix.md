# Observabilidade operacional do fluxo Pix

## Por que existe

O projeto possui evidências de carga e diagnóstico local, mas ainda precisa de uma visão operacional estável para responder se o core está saudável, se o SLA degradou e em qual fronteira existe backlog ou pressão de recursos.

Esta task deve reconstruir a observabilidade somente depois do congelamento técnico, sobre a arquitetura vigente. Não deve restaurar traces próprios no hot path nem métricas do antigo delivery push.

## Trabalho

- [ ] Definir sinais de liveness, readiness, restarts, CPU, memória, throttling e GC por serviço.
- [ ] Expor throughput, erros, latência de publicação, rebalance e consumer lag por tópico, grupo e partição Kafka.
- [ ] Expor no SPI pagamentos admitidos, reservas, rejeições, settlements, liberações, DLQ, profundidade da outbox e falhas do publisher.
- [ ] Expor no Notification Gateway Pulls, respostas vazias, tamanho real dos lotes, latência de long polling, cursor inválido/expirado, cache hit/miss, leitura histórica e posição observada no log.
- [ ] Expor no PostgreSQL conexões, query latency, locks, CPU, I/O, WAL e slow queries.
- [ ] Definir sinais de negócio e SLA: pagamentos originais, outcomes finais, rolling throughput e latências relevantes.
- [ ] Criar uma visão de saúde geral e uma visão ponta a ponta do fluxo PACS.008 -> SPI -> PACS.002 -> notificação Pull.
- [ ] Criar visão específica para Kafka, incluindo a retenção e a aproximação de cursores do início retido do log.
- [ ] Definir alertas mínimos para serviço indisponível, restart loop, throttling, lag crescente, outbox acumulando, DLQ recebendo mensagens, cursor expirando e SLA degradado.
- [ ] Padronizar dimensões de baixa cardinalidade e impedir que payment ID, communication ID ou cursor se tornem labels de métricas.
- [ ] Escolher a fonte mínima para cada sinal entre Micrometer/Actuator, exporters, JFR e logs estruturados.
- [ ] Integrar a coleta ao ambiente de teste sem tornar a stack de observabilidade requisito funcional do core.

## Limites

- Não existem subscribers push, ACK, lease, `IN_FLIGHT`, deliveries por status ou scheduler de redelivery para observar.
- A observabilidade não altera semântica, contratos ou recursos homologados do core.
- Dashboards Kubernetes permanecem condicionados a uma futura priorização de deploy Kubernetes.
