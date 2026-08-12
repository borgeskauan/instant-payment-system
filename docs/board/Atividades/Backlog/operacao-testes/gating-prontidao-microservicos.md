# Gating de prontidão dos microserviços

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
