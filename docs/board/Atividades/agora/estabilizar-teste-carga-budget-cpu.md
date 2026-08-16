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

### Experimento diagnóstico atual

Usar `mixed-outcomes-2k-diagnostic` para reproduzir o atraso do ingresso
PACS.008 sem repetir o run de 15 minutos: 2.000 TPS, 15 segundos de warmup, 60
segundos ativos e 30 segundos de drain. Mix, participantes, funding e replays de
5% permanecem idênticos a `mixed-outcomes-2k-15m`.

Executar com `--jfr --spi-trace --postgres-statements`. Além dos artefatos já
existentes, o bundle inclui:

- `diagnostics/postgres-activity.csv`, com waits e bloqueadores a cada 250 ms;
- `diagnostics/postgres-io.csv`, com snapshots antes/depois;
- `diagnostics/postgres-statements.csv`, com tempos de I/O por query;
- `diagnostics/container-stats.csv`, com CPU, memória e I/O a cada segundo.

O objetivo desta execução é classificar a limitação do ingresso como CPU, I/O,
lock, conexão ou combinação desses fatores. O run é diagnóstico, pode resultar
em `valid: false` e não autoriza tuning nem mudança dos buckets por si só.

- [x] Identificar o primeiro serviço, recurso ou estágio que satura quando o
  budget é respeitado.
- [x] Correlacionar a saturação com perda de geração, crescimento de lag,
  aumento de latência ou drain prolongado.
- [x] Separar custo de ingresso HTTP, produção/consumo Kafka, processamento no
  SPI, PostgreSQL, outbox, claim, lease e dispatch do notification-gateway.
- [ ] Para a hipótese de buckets, medir locks e waits em
  `funds_bucket_entity`, duração das transações de `pacs.002`, custo de bloquear
  pagador e recebedor e ocorrência de insuficiência artificial apesar de saldo
  agregado disponível.
- [ ] Se buckets forem relevantes, executar a task
  [`Substituir buckets por reserva no saldo do participante`](../Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md)
  como experimento isolado e comparar antes/depois.
- [x] Se o gargalo estiver em outro componente, atacar primeiro esse componente
  com uma alteração isolada e repetir o benchmark.
- [ ] Se as medições não permitirem atribuição, instrumentar o ponto ambíguo e
  repetir o diagnóstico sem antecipar uma solução.
- [ ] Registrar para cada intervenção a hipótese, evidência anterior, mudança,
  resultado posterior e decisão de manter ou descartar.

### Resultado do primeiro diagnóstico curto

O smoke funcional final
`postgres-diagnostics-smoke-final-2/20260815_135645` preservou os 1.250
pagamentos esperados, outcomes e replays sem violações funcionais. Em seguida,
o run `postgres-ingress-diagnostic/20260815_135825`, na revisão `60a0345` mais a
instrumentação ainda não commitada desta task, encontrou o limite antes do
processamento financeiro:

- o `kafka-producer`, front door HTTPS/mTLS, usou em média `99,86%` de um core
  na janela ativa; 44 de 47 amostras ficaram em pelo menos `95%`;
- foram iniciados 6.662 pagamentos originais na janela ativa. O cliente recebeu
  1.222 respostas HTTP 200 no run inteiro e 7.116 tentativas terminaram sem
  status; 6.754 delas chegaram ao timeout de 5–5,5 segundos;
- o perfil JFR do front door foi dominado por operações criptográficas de
  X25519/TLS. Isso sustenta como próxima hipótese o custo e o ciclo de vida das
  conexões mTLS sob concorrência; ainda não constitui autorização para mudar
  TLS, pooling ou timeout;
- o PostgreSQL também mostrou pressão, com `76,82%` de CPU média durante o
  ativo, mas sem bloqueadores, deadlocks, arquivos temporários ou leituras de
  disco no intervalo. Das sessões ativas observadas, `63,7%` aguardavam WAL
  (`WALWrite`, `WalSync` ou `WalWrite`) e `33,45%` estavam executando em CPU;
- Kafka, SPI e notification-gateway ficaram respectivamente em `29,50%`,
  `23,88%` e `15,91%` de CPU média no ativo. O lag observado após o drain era
  zero; portanto esses estágios processaram a carga admitida, mas ainda não
  foram exercitados a 2.000 pagamentos/s;
- no trace amostrado do SPI, `request_consumed -> request_saved` teve p95 de
  `198 ms` e `status_received -> settlement_completed` p95 de `302 ms`. Esses
  números são diagnóstico da carga admitida, não capacidade comprovada.

Conclusão atual: o primeiro gargalo observado é a saturação de CPU do ingresso
HTTPS/mTLS. O bloqueio dos requests prende os workers do gerador; isso não prova
um limite autônomo do load-tool. Como registros que já deram timeout no cliente
continuam podendo chegar ao Kafka, o próximo experimento deve caracterizar o
front door e a reutilização/renovação de conexões antes de qualquer mudança em
buckets ou PostgreSQL. A pressão de WAL fica registrada como possível segundo
limite para reavaliação quando o ingresso conseguir admitir a carga contratada.

### Evidência nativa de handshakes TLS

Na revisão `d249142`, com apenas a instrumentação ainda não commitada desta
task, o smoke `tls-handshake-smoke-2/20260815_143425` validou a compatibilidade
do evento JFR nativo `jdk.TLSHandshake`: os outcomes e replays permaneceram
corretos e o `kafka-producer` registrou 257 handshakes completos.

O diagnóstico `tls-handshake-diagnostic/20260815_143609` repetiu o workload
curto de 2.000 TPS e encontrou:

- 6.717 handshakes TLS 1.3 completos no `kafka-producer`, todos com
  `TLS_AES_128_GCM_SHA256`: 18 antes da geração, 990 no warmup, 4.550 na janela
  ativa e 1.159 no drain;
- os handshakes ativos ocorreram em ondas, não apenas na abertura inicial: houve
  novas ondas entre os segundos 11–18 e 45–56 da janela ativa, além de ondas no
  drain;
- o ingresso permaneceu saturado durante essas renovações, com CPU média de
  `101,47%`, máximo de `106,04%` e 45 de 48 amostras ativas em pelo menos
  `95%`;
- o load-tool iniciou 14.226 tentativas HTTP entre `pacs.008`, `pacs.002` e
  replays. Somente 1.243 receberam 2xx e 12.983 terminaram sem status;
- na janela ativa foram iniciados 6.903 pagamentos originais, com mínimo rolling
  de zero e máximo de 642 pagamentos/s. O SPI continuou muito abaixo do piso
  contratado, portanto o run é somente diagnóstico.

O evento nativo informa a conclusão e o instante do handshake, mas nesta JVM
reporta duração zero; ele não explica sozinho por que cada conexão foi criada ou
encerrada. Ainda assim, a quantidade e a recorrência tardia das ondas descartam
a hipótese de que o custo X25519 observado estivesse limitado ao estabelecimento
inicial das conexões. A próxima comparação deve alterar uma única variável do
ciclo de vida/reutilização das conexões HTTP e repetir o mesmo diagnóstico. Não
há autorização, nesta evidência, para mudar buckets, PostgreSQL, protocolo TLS
ou timeout simultaneamente.

### A/B do pool HTTP/1.1 por PSP

O B manteve protocolo, mTLS, timeout, workload, recursos e servidor. A única
intervenção foi limitar o pool do load-tool a 32 conexões HTTP/1.1 por PSP,
preservando keep-alive. Os CSVs existentes passaram a registrar aquisição,
escrita efetiva e a informação `GotConnInfo.Reused`, sem alterar o
`sla-report.json`. Ele foi executado sobre a revisão `56129a0`, mais as mudanças
de pool e observação ainda não commitadas desta intervenção.

O smoke `http11-pool-smoke/20260815_151103` completou 1.250 pagamentos, 1.250
PACS.002 e 127 replays, todos aceitos e com outcomes corretos. Em seguida, o B
`http11-pool-32-diagnostic/20260815_151254` produziu:

| Evidência | A sem limite efetivo | B com pool 32 |
| --- | ---: | ---: |
| tentativas HTTP totais | 14.226 | 153.976 |
| respostas 2xx | 1.243 | 149.640 |
| tentativas sem status | 12.983 | 4.336 |
| handshakes TLS completos | 6.717 | 4.416 |
| handshakes por 100 tentativas | 47,22 | 2,87 |
| pagamentos originais totais | 8.709 | 134.999 |
| pagamentos originais ativos | 6.903 | 132.648 |
| CPU média do ingresso no ativo | `101,47%` | `81,74%` |

O B realizou 10,8 vezes mais tentativas com 34,3% menos handshakes absolutos; a
taxa normalizada de handshake caiu 93,9%. Das tentativas do B, 153.026
adquiriram e escreveram uma conexão e 149.640 receberam 2xx. Entre pagamentos
originais ativos, a espera para adquirir conexão teve p50 de `2,87 ms`, p95 de
`80,83 ms` e p99 de `2.627,86 ms`. A espera não cresceu progressivamente: nos
últimos 20 segundos ativos ela caiu para aproximadamente `0,025 ms`, enquanto
foram escritos 40.000 pagamentos, média exata de 2.000/s e faixa alinhada de
1.998–2.002/s.

`GotConnInfo.Reused` não é uma contagem de novos handshakes. O transporte Go
pode concluir um dial já iniciado e colocar a conexão no pool depois que outra
conexão atendeu a request; por isso o JFR do servidor é a fonte autoritativa
para handshakes. No B, os handshakes ficaram concentrados até o segundo 29 do
ativo, com somente três eventos posteriores no segundo 47.

O B ainda não aprova performance. O começo do ativo ficou abaixo do piso e foi
seguido por catch-up, resultando em mínimo rolling zero, máximo `8.945/s` e
outcomes que não couberam no deadline. Entretanto, a fila do cliente drenou e
o trecho estável final sustentou a escrita de 2.000/s; portanto o pool fixo de
32 fica mantido como candidato para o workload longo, não como tuning final.

Quando o ingresso estabilizou nos últimos 20 segundos, sua CPU média caiu para
`39,89%`, enquanto o PostgreSQL chegou a `101,54%`. No ativo inteiro, 435
observações de sessões PostgreSQL aguardavam WAL e 423 executavam sem wait. O
primeiro limite foi deslocado do ingresso para o caminho SPI/PostgreSQL; buckets
continuam sendo hipótese, ainda sem autorização para mudança arquitetural.

### Próxima intervenção — persistência em batch no notification-gateway

O PostgreSQL permanece limitado a 1 vCPU. A próxima comparação reduz trabalho
por notificação sem relaxar durabilidade, idempotência ou a entrega
at-least-once.

O consumer do gateway já busca até 500 registros por poll e confirma offsets em
modo `BATCH`, mas o listener recebe um `ConsumerRecord` por chamada e executa um
`INSERT ... ON CONFLICT DO NOTHING` isolado. Assim, o batch Kafka atual não é um
batch transacional no PostgreSQL. O diagnóstico registrou aproximadamente 20
mil inserts individuais de `notification_delivery`, enquanto inserts e ACKs
individuais responderam por cerca de 92% das observações de `WALWrite` e
`WalSync` atribuíveis a queries conhecidas.

A primeira intervenção deve alterar somente o ingresso do gateway:

- o listener recebe a lista de registros entregue pelo poll;
- todos os registros são decodificados e persistidos por uma operação bulk em
  uma única transação;
- o método retorna, e o offset Kafka pode ser confirmado, somente depois do
  commit bem-sucedido;
- falha de banco aborta o batch e permite reprocessamento pelo Kafka;
- `ON CONFLICT (communication_id) DO NOTHING` preserva o reprocessamento
  idempotente;
- claim, dispatch, ACK, retry, SPI, buckets e PACS.008 permanecem inalterados.

Depois dos testes funcionais, repetir exatamente o diagnóstico curto. A
comparação deve verificar quantidade de chamadas/commits, waits de WAL, CPU do
PostgreSQL, throughput, latência e outcomes. A alteração só permanece se reduzir
o custo do banco sem perda ou regressão funcional.

O agrupamento de ACKs é uma segunda intervenção independente. Ele será desenhado
e medido somente depois desse A/B. Se os ACKs individuais permanecerem como a
principal fonte de WAL, poderão ser acumulados em uma fila limitada e
persistidos por update bulk; uma falha antes do commit deve resultar em
redelivery, nunca em perda. A query PACS.008 e a arquitetura de buckets só serão
reavaliadas depois dessas medições.

### Resultado do A/B de persistência em batch

A comparação final usou condições simétricas para evitar dois contaminantes
encontrados nas primeiras tentativas: JVM/TLS frios no primeiro A e trabalho
residual do A entregue durante o primeiro smoke B. Para cada variante foram
removidos apenas os volumes da stack, preservado o build cache, aguardada a
inicialização dos consumers, executado um smoke de aquecimento e, sem reiniciar
os serviços, executado o diagnóstico com `--jfr --spi-trace
--postgres-statements`. Runs frios, sem instrumentação ou com trabalho residual
foram preservados, mas não entram nesta comparação.

Os runs comparáveis são:

- A por registro: `notification-per-record-final-diagnostic/20260815_224502`;
- B por poll/batch: `notification-ingress-batch-final-diagnostic/20260815_225155`;
- smoke funcional B: `notification-ingress-batch-final-smoke/20260815_225026`.

O smoke B iniciou e recebeu `2xx` para 1.134 pagamentos, enviou os 1.134
PACS.002 e terminou com zero violações de cenário ou replay. Happy-path produziu
`ACSC`; insufficient-funds produziu `RJCT/AM04`. O `valid: false` do smoke foi
exclusivamente consequência do piso rolling do perfil curto, não de regressão
funcional.

| Evidência | A por registro | B por poll/batch |
| --- | ---: | ---: |
| pagamentos originais totais iniciados | 8.124 | 91.479 |
| respostas HTTP 200 | 618 | 86.856 |
| tentativas sem status | 7.506 | 4.623 |
| pagamentos originais ativos | 6.586 | 89.936 |
| throughput ativo médio | `109,767/s` | `1.498,933/s` |
| throughput rolling máximo | `512/s` | `8.911/s` |
| notificações persistidas observadas por `pg_stat_statements` | 10.183 | 55.831 |
| delta de `xact_commit` no PostgreSQL | 32.336 | 29.848 |
| `xact_rollback` | 0 | 0 |
| commits globais por notificação persistida | `3,175` | `0,535` |
| amostras ativas `WALWrite/WalSync` no insert | 165 | 0 |
| CPU média do PostgreSQL no ativo | `71,63%` | `77,64%` |
| CPU média do notification-gateway no ativo | `38,57%` | `40,61%` |
| memória média do PostgreSQL no ativo | `100,7 MiB` | `120,0 MiB` |
| memória média do notification-gateway no ativo | `295,0 MiB` | `302,5 MiB` |

`pg_stat_statements.calls` continua contando cada execução preparada dentro do
JDBC batch e, portanto, não representa a quantidade de transações do listener.
O efeito transacional aparece em `pg_stat_database`: o B processou 5,48 vezes
mais inserts de delivery com 7,7% menos commits globais, reduzindo em 83,2% a
razão global de commits por notificação observada. O volume absoluto de WAL dos
inserts cresceu com a workload admitida, mas as amostras `WALWrite/WalSync`
atribuídas ao insert caíram de 165 para zero. No B, 640 dessas amostras ficaram
atribuídas ao update individual de ACK, que passa a ser a principal hipótese de
redução de WAL no gateway.

A mudança fica mantida: ela preservou o comportamento funcional, reduziu a
pressão transacional e admitiu uma workload muito maior sem elevar
proporcionalmente CPU ou memória. Ela não aprova a performance. O B permaneceu
abaixo do piso sustentado, teve mínimo rolling zero, catch-up de até 8.911/s e
outcomes que não couberam no deadline. O `kafka-producer` continuou saturado em
aproximadamente um core e é novamente parte do caminho crítico sob a carga
maior. O lag dos três consumer groups estava em zero na observação posterior ao
run, mas isso não substitui as violações de deadline registradas pelo relatório.

O próximo experimento pode avaliar ACKs em batch como uma intervenção separada.
Não há, neste resultado, autorização para alterar simultaneamente concorrência,
PostgreSQL, SPI, buckets, claim ou dispatch.

## Hipóteses já conhecidas, não assumidas

- Manter, como candidato medido, o pool HTTP/1.1 fixo de 32 conexões por PSP.
  Validá-lo no workload longo antes de tratá-lo como configuração final; não
  alterar timeout ou protocolo no mesmo experimento.
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
