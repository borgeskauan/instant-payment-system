# Estabilizar teste de carga dentro do budget de CPU

## Por que existe

O sistema precisa sustentar pelo menos 2.000 pagamentos originais em qualquer
janela contínua de um segundo durante os 15 minutos ativos, dentro de um budget
fixo de aproximadamente 3 vCPUs por stack. Os
replays configurados fazem parte do ambiente instável prometido, mas são carga
adicional e não substituem os pagamentos originais.

Esta task não parte de um gargalo ou de uma solução escolhida. Primeiro ela
caracteriza o sistema atual, identifica com evidências o primeiro recurso ou
componente saturado e somente então escolhe uma intervenção. A arquitetura de
buckets, o gateway, Kafka, PostgreSQL, pools, polling e concorrência são
hipóteses a investigar, não mudanças previamente aprovadas.

O resultado deve ser um experimento repetível que sirva de base para o perfil de
recursos em Kubernetes e, depois da estabilização de uma stack, para validar
duas stacks compartilhando o mesmo PostgreSQL.

## Método

- Executar o primeiro baseline antes de qualquer mudança pendente no caminho
  exercitado, preservando inclusive a implementação atual de buckets e replay.
- Fixar workload, código, ambiente, limites de recursos e configuração durante
  cada comparação.
- Distinguir execução técnica de aprovação: o runner e o relatório devem concluir
  para preservar evidências, enquanto throughput rolling, p99 ou correção fora
  do contrato tornam `valid: false`.
- Tratar média e total como diagnóstico. Catch-up e picos não compensam nenhuma
  rolling window de um segundo abaixo do piso.
- Não fazer tuning ou refatoração sem uma hipótese sustentada pelas medições.
- Alterar uma variável relevante por vez e repetir o mesmo benchmark.
- Manter uma intervenção somente quando houver melhora mensurável, sem regressão
  funcional nem deslocamento do gargalo que torne o resultado pior.
- Se a evidência for inconclusiva, adicionar apenas a instrumentação necessária
  e medir novamente antes de alterar a arquitetura.

## Workloads

- Workload oficial: `cd load-test && ./run-load-test.sh --profile
  mixed-outcomes-2k-15m <run-tag>`.
- Meta: pelo menos 2.000 pagamentos originais iniciados em toda rolling window
  de um segundo integralmente contida nos 15 minutos ativos.
- Replays: 5% de `pacs.008` e 5% de `pacs.002`, sempre como carga adicional.
- Mix funcional: 80% happy-path (`ACSC`) e 20% insufficient-funds
  (`RJCT/AM04`), preservando a distribuição hot-pair.
- `mixed-outcomes-smoke` permanece a checagem funcional rápida.
- `uniform-smoke` permanece o controle happy-path e não substitui o workload
  oficial.

## Fase 1 — Baseline do sistema atual

- [ ] Registrar a revisão, o estado do worktree, as imagens, a configuração
  efetiva e os limites de CPU/memória usados no experimento.
- [ ] Delimitar quais serviços compõem o budget de 3 vCPUs por stack e quais
  recursos são compartilhados ou pertencem ao gerador de carga.
- [ ] Executar restart completo e preparação automática do ambiente antes do
  baseline.
- [ ] Rodar `mixed-outcomes-smoke` e confirmar os outcomes externos antes do run
  longo.
- [ ] Rodar `mixed-outcomes-2k-15m` sem alterar a implementação atual.
- [ ] Preservar os artefatos mesmo quando a geração, throughput ou SLA não
  atingirem a meta.
- [ ] Registrar média, mínimo e máximo rolling de pagamentos originais/s, carga
  adicional de replay, p50, p95, p99, max, Kafka lag, CPU, memória e duração do
  drain.
- [x] Descartar `baseline-buckets/20260814_023552` como baseline comparável: o
  scheduler aplicou a taxa ativa a um cursor atrasado do warmup. A análise
  posterior encontrou média `2.113,898`, mínimo rolling `0` e máximo rolling
  `10.563` pagamentos/s, portanto picos mascaravam períodos sem carga.
- [ ] Registrar PostgreSQL CPU, I/O, conexões, locks, waits e query latency.
- [ ] Repetir o baseline antes de qualquer intervenção se houver indício de
  ruído, interferência externa ou resultado atípico.

## Fase 2 — Diagnóstico e decisão

- [ ] Identificar o primeiro serviço, recurso ou estágio que satura quando o
  budget é respeitado.
- [ ] Correlacionar a saturação com perda de geração, crescimento de lag,
  aumento de latência ou drain prolongado.
- [ ] Separar custo de ingresso HTTP, produção/consumo Kafka, processamento no
  SPI, PostgreSQL, outbox, claim, lease e dispatch do notification-gateway.
- [ ] Para a hipótese de buckets, medir locks e waits em
  `funds_bucket_entity`, duração das transações de `pacs.002`, custo de bloquear
  pagador e recebedor e ocorrência de insuficiência artificial apesar de saldo
  agregado disponível.
- [ ] Se buckets forem relevantes, executar a task
  [`Substituir buckets por reserva no saldo do participante`](../Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md)
  como experimento isolado e comparar antes/depois.
- [ ] Se o gargalo estiver em outro componente, atacar primeiro esse componente
  com uma alteração isolada e repetir o benchmark.
- [ ] Se as medições não permitirem atribuição, instrumentar o ponto ambíguo e
  repetir o diagnóstico sem antecipar uma solução.
- [ ] Registrar para cada intervenção a hipótese, evidência anterior, mudança,
  resultado posterior e decisão de manter ou descartar.

## Hipóteses já conhecidas, não assumidas

- Preservar a reutilização atual de conexões HTTP persistentes entre PSP e
  `kafka-producer`; configurar pool, limites ou timeouts somente se as medições
  apontarem esse caminho.
- Avaliar claim e dispatch no `notification-gateway` separadamente. Filas
  limitadas por ISPB são apenas uma possível resposta caso o ciclo atual se
  prove gargalo.
- Medir se o writer sequencial por ISPB limita participantes quentes antes de
  alterar sua concorrência.
- Medir separadamente query de claim, updates de lease e polling da outbox antes
  de alterar índices, batch ou intervalo do worker.
- Considerar pools, consumers, producers e polling dos demais serviços somente
  quando aparecerem no caminho crítico observado.

## Fase 3 — Estabilização da arquitetura medida

- [ ] Rebalancear CPU por serviço dentro do limite total somente com base nos
  gargalos observados, não no melhor resultado local isolado.
- [ ] Definir memória alvo por serviço junto com CPU para evitar OOM ou swap.
- [ ] Ajustar concorrência de consumers/producers apenas quando a comparação
  demonstrar benefício dentro do budget final.
- [ ] Sustentar o piso de 2.000 pagamentos originais em toda rolling window de
  um segundo, dentro do SLA e com outcomes/replays corretos.
- [ ] Definir critério de estabilidade: variação aceitável entre runs, ausência
  de degradação progressiva e ambiente quiescente ao final segundo as heurísticas
  observáveis atuais.
- [ ] Executar múltiplos runs consecutivos, cada um após restart completo, e
  verificar repetibilidade.
- [ ] Atualizar o load-test para registrar automaticamente o perfil efetivo de
  CPU/memória quando isso ainda não estiver presente nos artefatos.
- [ ] Documentar requests, limits e justificativa por serviço para Kubernetes.

## Fase 4 — Duas stacks com PostgreSQL compartilhado

Esta fase começa somente depois que uma stack estiver funcionalmente correta e
estável dentro do budget.

- [ ] Planejar duas stacks/instalações no mesmo cluster compartilhando o mesmo
  PostgreSQL.
- [ ] Definir separação de dados, tópicos, consumer groups, ISPBs e métricas
  entre stacks.
- [ ] Validar conexões, locks, query latency, CPU, I/O e p95/p99 no PostgreSQL
  compartilhado.
- [ ] Validar isolamento de CPU/memória entre stacks.
- [ ] Confirmar que o perfil final de recursos continua válido ou registrar a
  nova restrição de capacidade imposta pelo recurso compartilhado.

## Critérios de conclusão

- existe um baseline preservado do sistema anterior às intervenções;
- o gargalo inicial e cada deslocamento posterior estão sustentados por
  evidências, não por suposição;
- alterações mantidas possuem comparação antes/depois sob o mesmo experimento;
- o workload oficial sustenta o piso de 2.000 pagamentos originais em toda
  rolling window de um segundo, mais replays, com correção funcional e dentro
  do budget/SLA final;
- runs repetidos não apresentam degradação progressiva;
- o perfil final de CPU e memória está documentado para Kubernetes;
- o impacto de duas stacks com PostgreSQL compartilhado está medido e
  documentado.
