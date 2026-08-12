# Observabilidade operacional do fluxo Pix

**Por que existe**

O projeto já tem testes de carga, JFR, traces CSV e métricas de infraestrutura no ambiente local, mas ainda falta uma visão operacional clara para responder rapidamente se o sistema está saudável, se houve degradação e onde está o gargalo. A observabilidade precisa cobrir tanto saúde técnica dos serviços quanto o fluxo de negócio Pix ponta a ponta.

As metas principais são: verificar saúde da aplicação enquanto ela roda, acompanhar throughput e latência do fluxo, detectar degradação de SLA, identificar gargalos entre Kafka, SPI, PostgreSQL e notification-gateway, e comparar o comportamento antes/depois de mudanças.

**Tarefas**

- [ ] Definir métricas de saúde por serviço: container/pod up, restarts, readiness/liveness, CPU, memória, CPU throttling, JVM heap, direct memory e GC.
- [ ] Definir métricas Kafka: consumer lag por tópico/grupo/partição, records consumed/sec, records produced/sec, producer latency, retries, errors e rebalances.
- [ ] Definir métricas do SPI: requests consumidas/sec, statuses recebidos/sec, settlements/sec, notificações enfileiradas/sec, latência de processamento, erros e tempo de queries no PostgreSQL.
- [ ] Definir métricas de DLQ no SPI: mensagens publicadas por tópico/motivo, falhas ao publicar na DLQ, total por tópico e idade da mensagem mais antiga.
- [ ] Definir métricas do notification-gateway: subscribers ativos, notificações/sec, deliveries pendentes por status, retries, falhas, expirados, mortos, tempo até ACK, lag do worker, erros gRPC e streams cancelados.
- [ ] Definir métricas de banco: conexões ativas, pool Hikari, query latency, locks, CPU, I/O e slow queries.
- [ ] Definir métricas de negócio/SLA: transações iniciadas, aceitas, confirmadas, não confirmadas, dentro/fora do SLA, p50, p95, p99 e máximo.
- [ ] Criar dashboard de saúde geral do ambiente: serviços, containers/pods, CPU, memória, restarts, throttling, Kafka e PostgreSQL.
- [ ] Criar dashboard do fluxo Pix ponta a ponta: incoming, consumed, settled, outbound status, notifications e confirmations.
- [ ] Criar dashboard de latência por etapa: `http_done -> request_consumed`, `request_consumed -> pacs008_received`, `pacs002_sent -> confirmation_received` e latência end-to-end.
- [ ] Criar dashboard de Kafka: lag, throughput, producer/consumer rate, partitions e rebalances.
- [ ] Criar dashboard de cold start: tempo até readiness, tempo até primeiro consumo, lag inicial, drain rate e tempo até estabilizar p95/p99.
- [ ] Definir alertas mínimos para degradação: container down, restart loop, CPU throttling alto, consumer lag crescendo, DLQ recebendo mensagens, p95/p99 acima do alvo, transações não confirmadas e erros Kafka/gRPC/DB.
- [ ] Padronizar labels/dimensões das métricas: serviço, tópico, consumer group, partition, ISPB, tipo de evento e versão/build.
- [ ] Decidir quais métricas vêm de Micrometer/Actuator, Kafka exporter, Postgres exporter, cAdvisor/Node exporter, JFR ou traces próprios.
- [ ] Integrar a observabilidade aos testes de carga para comparar baseline vs mudança: throughput sustentado, p95/p99, lag, CPU por transação e memória por transação.
