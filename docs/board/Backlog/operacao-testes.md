# Operação e testes

Trabalho possível, mas ainda não priorizado. Tarefas ativas ou pausadas já priorizadas ficam em `docs/board/Atividades`.

## Auditoria completa das transações no SPI sem pesar o hot path

**Por que existe**

Para reduzir pressão no PostgreSQL durante os testes de carga, o SPI passou a persistir no caminho quente apenas os campos necessários para liquidação e roteamento da confirmação: `payment_id`, `amount`, `status`, `sender_bank_code` e `receiver_bank_code`.

Isso ajuda a medir o impacto de uma tabela operacional estreita, mas não resolve a necessidade real de auditoria. Em produção, o sistema precisa conseguir reconstituir a transação completa, incluindo payload recebido, dados das partes e metadados de origem. Essa auditoria não deve voltar a bloquear a liquidação dentro do SLA.

**Tarefas**

- [ ] Definir o modelo de auditoria completo da transação.
- [ ] Decidir se a fonte auditável principal será Kafka, tabela dedicada, outbox ou combinação desses mecanismos.
- [ ] Criar fluxo assíncrono para persistir payload/dados completos sem bloquear o settlement.
- [ ] Registrar metadados de origem: tópico, partição, offset, timestamp de consumo e identificador fim a fim.
- [ ] Manter tabela operacional estreita para o caminho quente de liquidação.
- [ ] Definir política de retenção e consulta para dados de auditoria.
- [ ] Medir o impacto da auditoria assíncrona no load test antes de considerá-la parte do fluxo padrão.

## Observabilidade operacional do fluxo Pix

**Por que existe**

O projeto já tem testes de carga, JFR, traces CSV e métricas de infraestrutura no ambiente local, mas ainda falta uma visão operacional clara para responder rapidamente se o sistema está saudável, se houve degradação e onde está o gargalo. A observabilidade precisa cobrir tanto saúde técnica dos serviços quanto o fluxo de negócio Pix ponta a ponta.

As metas principais são: verificar saúde da aplicação enquanto ela roda, acompanhar throughput e latência do fluxo, detectar degradação de SLA, identificar gargalos entre Kafka, SPI, PostgreSQL e notification-gateway, e comparar o comportamento antes/depois de mudanças.

**Tarefas**

- [ ] Definir métricas de saúde por serviço: container/pod up, restarts, readiness/liveness, CPU, memória, CPU throttling, JVM heap, direct memory e GC.
- [ ] Definir métricas Kafka: consumer lag por tópico/grupo/partição, records consumed/sec, records produced/sec, producer latency, retries, errors e rebalances.
- [ ] Definir métricas do SPI: requests consumidas/sec, statuses recebidos/sec, settlements/sec, notificações enfileiradas/sec, latência de processamento, erros e tempo de queries no PostgreSQL.
- [ ] Definir métricas de DLQ no SPI: mensagens publicadas por tópico/motivo, falhas ao publicar na DLQ, total por tópico e idade da mensagem mais antiga.
- [ ] Definir métricas do notification-gateway: subscribers ativos, notificações/sec, batches/sec, tamanho dos batches, flush por tamanho/tempo, erros gRPC e streams cancelados.
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

## Gating de prontidão dos microserviços

**Por que existe**

Os microserviços podem subir tecnicamente antes de estarem prontos para servir tráfego real. No fluxo atual, isso afeta principalmente o cold start: a aplicação pode aceitar carga antes de aquecer conexões, Kafka producers/consumers, gRPC streams, pools, caches e caminhos críticos de serialização/DB. Isso cria ruído nos testes e pode gerar degradação logo após deploy ou restart.

A meta é separar "processo está vivo" de "serviço está pronto para tráfego real". Cada serviço deve expor readiness apenas depois de validar dependências, inicializar recursos críticos e concluir o warmup necessário para o seu papel no fluxo Pix.

**Tarefas**

- [ ] Definir critérios de readiness por serviço: `kafka-producer`, SPI, notification-gateway, PSP, Kafka, PostgreSQL e DICT quando for separado.
- [ ] Separar liveness de readiness: liveness indica processo vivo; readiness indica apto a receber tráfego.
- [ ] Implementar readiness no `kafka-producer` apenas após conexão com Kafka, metadata dos tópicos carregada e producers aquecidos.
- [ ] Implementar readiness no SPI apenas após PostgreSQL/Flyway prontos, Kafka consumers criados, tópicos acessíveis, pools aquecidos e warmup do caminho crítico concluído.
- [ ] Implementar readiness no notification-gateway apenas após conexão com Kafka, listener ativo e servidor gRPC pronto.
- [ ] Implementar readiness no PSP apenas após conexão com notification-gateway, stream gRPC estabelecido, dependências HTTP/DICT disponíveis e warmup concluído.
- [ ] Definir se o serviço deve rejeitar tráfego com `503` enquanto não estiver ready.
- [ ] Atualizar Docker Compose para usar healthchecks que reflitam readiness real, não apenas porta aberta.
- [ ] Planejar adaptação futura para Kubernetes readiness/liveness probes.
- [ ] Integrar os gates ao script de load test para iniciar carga apenas depois de todos os serviços críticos estarem ready.
- [ ] Expor métricas de startup/warmup: tempo até liveness, tempo até readiness, tempo de warmup, falhas de dependência e último motivo de not-ready.
- [ ] Adicionar testes de contrato para readiness: dependência indisponível mantém serviço not-ready; warmup concluído torna serviço ready.

## Cenários realistas e reprocessamento no load-tool

**Por que existe**

O teste atual sustenta alta taxa com um padrão bastante controlado. Para aproximar o experimento de produção, o load-tool precisa fornecer perfis de teste automatizados para cenários diferentes: caminho feliz, saldo insuficiente, rejeições, duplicidade, replay, hot participants e variações de carga.

Kafka, retries, restarts e falhas de rede tornam duplicidade e replay inevitáveis. O fluxo precisa provar que reprocessar mensagens não duplica liquidação, que status já liquidado pode reemitir notificação de forma segura e que mensagens inválidas não travam o consumo.

**Tarefas**

- [ ] Adicionar perfis de teste no load-tool, com múltiplos arquivos `loadtool-config.json` por cenário.
- [ ] Permitir selecionar o perfil de teste no script de execução, por exemplo caminho feliz, saldo insuficiente, rejeições funcionais, duplicidade, replay e hot participants.
- [ ] Garantir que cada perfil defina carga, distribuição de participantes, valores, fundos provisionados, taxa esperada de confirmação e critérios de SLA.
- [ ] Gerar valores de transação variados em vez de valor fixo.
- [ ] Simular distribuição desigual entre ISPBs: poucos participantes quentes e muitos participantes frios.
- [ ] Criar cenários com hot ISPB, hot sender, hot receiver e hot partition.
- [ ] Variar taxa de chegada com ramp-up, pico, carga sustentada, queda e período ocioso.
- [ ] Misturar transações aprovadas e rejeitadas no mesmo run.
- [ ] Simular saldo insuficiente real para parte dos pagamentos.
- [ ] Medir impacto de rejeições e saldo insuficiente em throughput, p95/p99, consumer lag e uso de CPU.
- [ ] Validar que a taxa de confirmação considera apenas transações que deveriam confirmar.
- [ ] Reprocessar mensagens Kafka já consumidas e validar que não ocorre dupla liquidação.
- [ ] Reemitir status de pagamento já liquidado e validar replay idempotente da notificação.
- [ ] Testar duplicidade de `pacs.008` com o mesmo `EndToEndId`.
- [ ] Testar duplicidade de `pacs.002` para pagamento já confirmado.
- [ ] Validar que `notSettledPaymentIds` e atualizações de status continuam corretos com IDs duplicados.
- [ ] Expor métricas de duplicidade, replay e retries.
- [ ] Garantir que retry/replay não altera saldo nem gera confirmação inconsistente.
- [ ] Comparar cenário uniforme atual contra cenários realistas para identificar regressões escondidas.

## Estabilizar teste de carga dentro do budget de CPU

**Por que existe**

Os testes recentes usaram ajustes de CPU para entender gargalos e validar otimizações. Para o experimento ficar repetível e servir de base para deploy em Kubernetes, o ambiente precisa estabilizar dentro de um budget fixo de aproximadamente 3 vCPUs por stack. Isso permite planejar capacidade para rodar duas stacks/instalações do conjunto de serviços no cluster sem depender de CPU extra durante o teste.

Nesse desenho, as duas stacks devem compartilhar o mesmo PostgreSQL. Isso precisa ser validado explicitamente, porque o banco vira um recurso comum entre as stacks e pode mudar o gargalo, a configuração de pools, locks, conexões e isolamento de dados.

**Tarefas**

- [ ] Definir o budget alvo de CPU por serviço dentro do limite total de 3 vCPUs por stack.
- [ ] Rebalancear CPU no Docker Compose para refletir o budget alvo, não apenas o melhor resultado local.
- [ ] Definir memória alvo por serviço junto com CPU para evitar OOM ou swap durante o load test.
- [ ] Rodar baseline de 15 minutos com o budget de 3 vCPUs e registrar throughput, p95, p99, max, lag, CPU e memória.
- [ ] Validar que o fluxo sustenta a meta de TPS dentro do SLA com o budget definido.
- [ ] Identificar qual serviço satura primeiro quando o budget total é respeitado.
- [ ] Ajustar concorrência de consumers/producers para o budget final, evitando configuração que só funciona com CPU excedente.
- [ ] Definir critério de estabilidade: variação aceitável entre runs, ausência de backlog residual e ausência de degradação progressiva.
- [ ] Criar cenário de repetição com múltiplos runs consecutivos após restart completo.
- [ ] Documentar o perfil final de recursos para Kubernetes: requests, limits e justificativa por serviço.
- [ ] Planejar execução com duas stacks/instalações no mesmo cluster compartilhando o mesmo PostgreSQL.
- [ ] Definir como separar dados, tópicos, consumer groups, ISPBs e métricas entre stacks quando o banco for compartilhado.
- [ ] Validar impacto do PostgreSQL compartilhado em conexões, locks, query latency, CPU, I/O e p95/p99.
- [ ] Validar isolamento de CPU/memória entre stacks mesmo com banco compartilhado.
- [ ] Atualizar scripts de load test para registrar automaticamente o perfil de CPU/memória usado no run.
