# Estabilizar teste de carga dentro do budget de CPU

**Por que existe**

Os testes recentes usaram ajustes de CPU para entender gargalos e validar otimizações. Para o experimento ficar repetível e servir de base para deploy em Kubernetes, o ambiente precisa estabilizar dentro de um budget fixo de aproximadamente 3 vCPUs por stack. Isso permite planejar capacidade para rodar duas stacks/instalações do conjunto de serviços no cluster sem depender de CPU extra durante o teste.

Nesse desenho, as duas stacks devem compartilhar o mesmo PostgreSQL. Isso precisa ser validado explicitamente, porque o banco vira um recurso comum entre as stacks e pode mudar o gargalo, a configuração de pools, locks, conexões e isolamento de dados.

**Notas de performance**

- Preservar a reutilização de conexões HTTP persistentes entre o PSP e o `kafka-producer`, evitando um novo handshake mTLS por requisição.
- Criar o cliente HTTP e seu contexto TLS uma vez e reutilizá-los durante a vida da aplicação PSP; não recriá-los a cada envio.
- O gerenciamento das conexões pertence ao cliente PSP. O `kafka-producer`, como servidor, deve manter keep-alive e aceitar conexões persistentes; ele não cria um pool para conexões recebidas.
- O cliente HTTP atual já oferece reutilização implícita de conexões. Avaliar configuração e tuning explícitos de pool, limites e timeouts somente com base nos resultados dos testes de carga.
- Avaliar o desacoplamento entre claim e dispatch no `notification-gateway`. Hoje o worker aguarda o término dos grupos de ISPB antes de iniciar outro ciclo; filas limitadas por ISPB podem permitir novos claims sem introduzir writes concorrentes no mesmo stream.
- Medir se o envio sequencial de deliveries para um mesmo ISPB se torna gargalo em cenários com participante quente e avaliar alternativas que preservem um único writer por stream.
- Medir separadamente o custo da query de claim, dos updates de lease e do polling da outbox. Usar os resultados para avaliar índices, tamanho do batch e intervalo do worker.

**Tarefas**

- [ ] Usar `mixed-outcomes-2k-15m` como o workload oficial: `cd load-test && ./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>`.
- [ ] Sustentar 2.000 pagamentos originais/s durante os 15 minutos ativos; os replays configurados de 5% para `pacs.008` e 5% para `pacs.002` são carga adicional e não substituem originais.
- [ ] Validar o mix funcional 80% happy-path (`ACSC`) e 20% insufficient-funds (`RJCT/AM04`), a distribuição hot-pair e `sla-report.json` com `valid: true` antes de avaliar os gates de performance.
- [ ] Usar `mixed-outcomes-smoke` para checagens funcionais rápidas; `uniform-smoke` permanece controle happy-path e não substitui o workload oficial de 15 minutos.
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
