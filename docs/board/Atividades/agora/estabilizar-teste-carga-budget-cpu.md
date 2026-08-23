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
- Enquanto houver backlog e trabalho pronto, não exigir queda imediata da CPU do
  PostgreSQL como prova de otimização. Medir primeiro trabalho útil por unidade
  de tempo, custo por row/pagamento, inclinação do backlog e conclusão
  end-to-end; a CPU só ganha folga quando a capacidade ultrapassa a demanda.
- Se a evidência não separar as hipóteses, adicionar apenas a instrumentação
  necessária e medir novamente antes de alterar a arquitetura.

## Workloads

- Para um baseline isolado, executar `cd load-test &&
  ./prepare-performance-environment.sh`. O comando recria volumes, sobe a stack
  e espera readiness; ele não gera tráfego nem tenta inferir quiescência interna.
- Workload oficial: `cd load-test && ./run-load-test.sh --profile
  mixed-outcomes-2k-15m <run-tag>`.
- Os profiles oficial de 15 minutos e diagnóstico usam warmup explícito de
  `1.500 TPS` por `120 s`, com timeout de conclusão de `120 s`. O ativo só
  começa depois que as obrigações de warmup observáveis pelo load-tool terminam.
- Meta: pelo menos 2.000 pagamentos originais iniciados em toda rolling window
  de um segundo integralmente contida nos 15 minutos ativos.
- Replays: 5% de `pacs.008` e 5% de `pacs.002`, sempre como carga adicional.
- Mix funcional: 80% happy-path (`ACSC`) e 20% insufficient-funds
  (`RJCT/AM04`), preservando a distribuição hot-pair.
- `mixed-outcomes-smoke` permanece disponível como checagem funcional rápida,
  mas não é executado automaticamente pelo preparador.
- `uniform-smoke` permanece o controle happy-path e não substitui o workload
  oficial.

## Fase 1 — Baseline do sistema atual

- [ ] Registrar a revisão, o estado do worktree, as imagens, a configuração
  efetiva e os limites de CPU/memória usados no experimento.
- [ ] Delimitar quais serviços compõem o budget de 3 vCPUs por stack e quais
  recursos são compartilhados ou pertencem ao gerador de carga.
- [ ] Executar `prepare-performance-environment.sh` antes do baseline; não
  iniciar o run longo se o reset, a subida da stack ou a readiness falhar.
- [ ] Confirmar no próprio run que o gate concluiu todas as obrigações
  observáveis do warmup antes de abrir a janela ativa.
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
PACS.008 sem repetir o run de 15 minutos: warmup de 1.500 TPS por 120 segundos,
gate de conclusão de até 120 segundos, 2.000 TPS por 60 segundos ativos e 30
segundos de drain. Mix, participantes, funding e replays de 5% permanecem
idênticos a `mixed-outcomes-2k-15m`.

JFR, SPI trace e diagnósticos PostgreSQL ficam ativos por padrão. Além dos
artefatos já existentes, o bundle inclui:

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
os serviços, executado o diagnóstico com JFR, SPI trace e diagnósticos
PostgreSQL ativos. Runs frios, sem instrumentação ou com trabalho residual foram
preservados, mas não entram nesta comparação.

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

### Resultado do A/B de ACKs em batch

A comparação usou o A imutável
`load-test/results/preparation-workflow-verification/20260816_004331`, no commit
`1a4f395`, e o único B
`load-test/results/notification-ack-batch-diagnostic/20260816_021705`, sobre o
mesmo commit mais o diff de ACK batching ainda não staged e não commitado. O B
foi executado uma vez com o perfil `mixed-outcomes-2k-diagnostic`, terminou com
exit `1` por SLA inválido e preservou todos os diagnósticos. Os cinco parâmetros
efetivos, confirmados no container, foram batch `500`, flush `20 ms`, fila
`10.000`, retry `100 ms` e shutdown `5.000 ms`.

| Evidência | A — ACK individual | B — ACK em batch |
| --- | ---: | ---: |
| chamadas / rows / rows por chamada do ACK | `36.042 / 36.042 / 1,00` | `345 / 76.090 / 220,55` |
| tempo total / médio / máximo do ACK | `31.739,475 / 0,881 / 396,886 ms` | `22.366,045 / 64,829 / 683,428 ms` |
| WAL do ACK: records / bytes | `143.312 / 44.838.385` | `309.360 / 98.745.671` |
| WAL do ACK por row: records / bytes | `3,976 / 1.244,06` | `4,066 / 1.297,75` |
| waits ativos do ACK `WALWrite / WalSync` | `615 / 84` | `0 / 0` |
| delta global de commits / rollbacks | `39.586 / 0` | `4.993 / 0` |
| PostgreSQL CPU média/máxima; memória média/máxima | `102,69% / 113,94%; 242,9 / 271,6 MiB` | `101,99% / 109,52%; 216,4 / 239,6 MiB` |
| gateway CPU média/máxima; memória média/máxima | `15,42% / 41,82%; 310,8 / 318,2 MiB` | `18,05% / 72,84%; 295,4 / 301,2 MiB` |
| originais iniciados no ativo; TPS média/mínima/máxima | `120.000; 2.000 / 1.947 / 2.053` | `129.406; 2.156,767 / 1.944 / 5.377` |
| originais aceitos no run | `134.952 / 135.000` | `134.885 / 135.000` |
| PACS.002 aceitos; throughput ativo | `33.706; 490,883/s` | `57.947; 742,767/s` |
| notificações de pagador ativas / após o ativo | `0/s / 0` | `66,867/s / 955` |
| outcomes matched / missing / contradictory | `4.668 / 130.284 / 0` | `9.905 / 124.980 / 0` |
| violações de replay PACS.008 / PACS.002 | `0 / 0` | `0 / 50` |
| saturação da fila de ACK | não aplicável | ausente: `0` parks em `enqueue`; `364` parks somente no writer `nextBatch` |

As janelas de CPU e memória foram filtradas pelos instantes autoritativos
`active_started_at` e `generation_ended_at`. `pg_stat_statements` cobre o run
completo; por isso os totais do ACK também foram normalizados por row. A forma
do B é o bulk estável `UPDATE notification_delivery AS delivery ... FROM
unnest(...)`; a normalização do PostgreSQL substituiu o literal `ACKED` por um
parâmetro. As chamadas caíram 99,0%, o tempo por row caiu 66,6%, commits globais
caíram 87,4% e os waits ativos de WAL do ACK desapareceram. Entretanto, o B
atualizou 2,11 vezes mais rows, seu WAL por row não caiu (`+2,25%` records e
`+4,32%` bytes), e os totais brutos de WAL não podem fundamentar uma decisão
favorável.

O run B permanece **inválido para o gate pré-definido** e não autoriza manter a
mudança com base neste A/B isolado. Isso, porém, não caracteriza uma regressão
funcional do batching. A inspeção dos 50 replays PACS.002 ausentes mostrou que
todos foram selecionados a partir de PACS.002 originais iniciados entre
`generationEnd + 33,902 s` e `generationEnd + 33,950 s`. O drain configurado era
de `30 s`, mas o simulador antigo estendia o experimento até `generationEnd +
40 s` por somar o maior delay de replay. Como cada repetição venceria `10 s`
depois, todas ficaram aproximadamente `3,9 s` além desse deadline artificial.
Não houve replay ausente cujo instante agendado ainda coubesse no deadline, e os
50 PACS.002 originais receberam HTTP 2xx.

O batching mostrou ganhos locais e end-to-end, mas a fronteira temporal precisa
ser corrigida antes de uma nova comparação. O run B preservou ACSC e RJCT/AM04, melhorou
PACS.002, notificações e outcomes, reduziu chamadas em 99,0%, tempo por row em
66,6% e commits globais em 87,4%, além de eliminar os waits ativos de WAL do
ACK. Ainda assim, não se fará outro A/B nem run de 15 minutos nesta correção. A
admissão ativa não caiu, porém o pico de `5.377/s` evidencia catch-up, não um
novo estado estável. Não houve backpressure de produtores na fila, nem WARN de
retry observado durante a janela; o Minor de WARN repetido continua apenas
deferido para review final.

Antes de um novo diagnóstico, o gateway deve garantir que o batcher esteja
operacional durante toda a vida do servidor gRPC e serializar delivery e
encerramento do mesmo observer. O simulador passa a usar `generationEnd +
drain` como deadline absoluto: replays de originais iniciados antes de
`generationEnd` podem executar durante o drain, mas PACS.002 originais que só
começam no drain completam o fluxo normal sem entrar na população do seletor e
sem criar nova obrigação de replay. O drain deve ser ao menos igual ao maior
delay configurado. Nenhum campo público novo é acrescentado ao
`sla-report.json`.

A correção foi validada pelo smoke qualificado
`environment-setup-20260816_165447-1935946-attempt-2/20260816_165801` em uma
stack recriada sem volumes residuais: `1.250/1.250` pagamentos originais e
PACS.002 foram aceitos, ACSC e RJCT/AM04 ficaram completos, e os replays
PACS.008 (`64/64`) e PACS.002 (`62/62`) terminaram sem violações. O relatório
permaneceu inválido apenas porque o rolling mínimo observado foi `99/s` para o
alvo de `100/s`; o qualificador funcional aceitou o run e o Kafka ficou
quiescente ao final. O primeiro smoke antes da limpeza recebeu `28.000`
notificações residuais do experimento anterior e foi corretamente tratado como
contaminação ambiental, não como evidência da mudança.

Com o ACK removido da liderança de amostras ativas, o PostgreSQL continuou em
aproximadamente um core e a query de settlement PACS.002 passou a liderar, com
342 amostras ativas e `280.235,705 ms` de execução acumulada; a admissão
PACS.008 veio em seguida, com 274 amostras. Isso é a próxima hipótese medida,
não autorização para alterar SPI, buckets, claim, dispatch ou recursos. Nenhum
run de 15 minutos foi executado e este experimento não constitui aprovação do
SLA final.

## Hipóteses já conhecidas, não assumidas

- Manter HTTP/2-only com um cliente Go independente por PSP como boundary do
  ingresso. HTTP/1.1 não é candidato nem fallback; investigar o gargalo
  downstream sem alterar timeout ou protocolo no mesmo experimento.
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
  de degradação progressiva e ambiente quiescente ao final. Lag Kafka zero não
  basta enquanto outbox e deliveries persistidas puderem continuar pendentes.
- [ ] Verificar repetibilidade com cada execução medida partindo de volumes
  novos. Reutilizar uma única preparação entre runs somente depois de existir
  uma fronteira confiável de quiescência ou limpeza end-to-end.
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

## Baseline consolidada após estabilização curta

A arquitetura vigente usa o saldo do participante como saldo disponível
autoritativo. Um pagamento em `WAITING_ACCEPTANCE` representa liquidez já
reservada. Pagamentos e status entram no Kafka com chave igual ao ISPB do PSP
autenticado, e os dois grupos do SPI usam oito consumers para oito partições.

O ingresso de transferência central é exclusivamente HTTP/2 sobre mTLS. Cada
PSP simulado mantém seu próprio cliente Go de longa duração, e todos os PSPs
concluem um prewarm autenticado antes do início do relógio da workload.

No diagnóstico controlado de 60 segundos, os PACS.008 ativos com HTTP 2xx
subiram de `693` para `127.456`, os timeouts caíram de `5.717` para `0` e os
handshakes TLS durante a janela ativa caíram de `6.019` para `0`. O piso rolling
melhorou de `0` para `567 TPS`, portanto o piso sustentado de `2.000 TPS` ainda
não foi comprovado.

O gargalo medido deslocou-se para a persistência de PACS.008 no SPI/PostgreSQL:
o PostgreSQL consumiu em média `103,786%` de CPU e o lag imediato do grupo de
pagamentos foi `42.271`, enquanto o lag de status foi zero. Os outcomes ausentes
são consistentes com trabalho downstream atrasado, mas a quiescência posterior
do Kafka prova consumo dos offsets, não persistência ou entrega eventual.

O próximo experimento deve atacar exclusivamente a persistência de PACS.008.
Nenhuma qualificação de 15 minutos está autorizada antes de comprovar o piso
sustentado no diagnóstico curto.

## Fast path de persistência PACS.008

O experimento curto comparou o controle
`consolidated-baseline/20260818_185932` com
`pacs008-fast-path/20260818_195402`. A intervenção moveu para Java a
classificação local do batch e substituiu a CTE de admissão por um `INSERT ...
ON CONFLICT DO NOTHING RETURNING payment_id`. Somente conflitos consultam o
estado já persistido; o fluxo de reserva, auditoria, outbox e ACK permaneceu na
mesma transação.

O smoke da variante qualificou na primeira tentativa: `1.250/1.250` pagamentos
originais, `1.000/1.000` PACS.002 e `112/112` replays foram aceitos. Os 287
testes do SPI também passaram, incluindo concorrência entre PACS.008
divergentes com o mesmo ID, que produziu uma única criação e uma única reserva.

No diagnóstico de 60 segundos:

- os `135.000/135.000` pagamentos originais e os `7.325/7.325` replays
  receberam HTTP 2xx;
- a latência HTTP PACS.008 p95 caiu de `264,820 ms` para `16,890 ms`, e a p99
  de `698,384 ms` para `38,731 ms`;
- a janela ativa passou de média/mínimo/máximo rolling
  `2.124,8 / 196 / 5.959 TPS`, com catch-up, para
  `2.000 / 1.978 / 2.021 TPS`;
- o tempo acumulado da admissão caiu de `346.388,873 ms` para
  `251.241,847 ms`; a consulta posterior dos conflitos consumiu apenas
  `2.319,808 ms`;
- o PostgreSQL permaneceu saturado em aproximadamente um core: CPU média
  `102,949%` no controle e `100,749%` na variante.

O fast path removeu o gargalo do ingresso HTTP, mas não estabilizou o sistema
completo. O relatório continuou inválido porque o menor rolling ficou `22 TPS`
abaixo do piso e porque o processamento downstream não concluiu os outcomes no
deadline. A transição PACS.002 `ACCEPTED` passou a liderar o PostgreSQL, com
`469.731,977 ms` acumulados, média de `1.008,009 ms` por chamada e máximo de
`44.900,790 ms`. A nova admissão também apresentou espera `transactionid`
quando replays alcançaram originais ainda não commitados; isso explica seus
outliers de conflito e deve ser considerado no próximo desenho, sem reintroduzir
a antiga CTE.

Portanto, a evidência mantém a simplificação do ingresso, mas não qualifica o
SLA final. O próximo experimento deve atacar uma única causa no caminho
PACS.002/settlement ou reduzir o convoy transacional causado por batches que
misturam trabalho novo e conflitos, preservando workload, recursos e o fast
path já medido. Nenhum run de 15 minutos foi executado nesta intervenção.

## Batch PACS.002 com concorrência independente

O controle `pacs008-fast-path/20260818_195402` usava oito consumers tanto para
PACS.008 quanto para PACS.002. A variante
`status-concurrency-one/20260818_204528` separou as factories Kafka, preservou
oito consumers PACS.008 e atribuiu as oito partições de status a um único
consumer PACS.002. Workload, recursos, `max.poll.records`, fetch e restante da
stack permaneceram iguais.

O smoke final qualificou `1.250/1.250` pagamentos, `1.000/1.000` PACS.002 e
`112/112` replays. A primeira preparação parou antes da workload por timeout de
um dos 100 prewarms HTTP/2; uma tentativa aquecida ficou funcionalmente correta
mas parcial em `1.249/1.250`, e a tentativa seguinte qualificou integralmente.
Nenhuma dessas tentativas parciais foi usada como benchmark.

No diagnóstico medido:

- o tamanho médio das transições PACS.002 subiu de `49,118` para `319,714`
  rows por chamada, um ganho de `6,51x`;
- as chamadas do `UPDATE ... RETURNING` caíram de `466` para `77`;
- o tempo acumulado dessa query caiu de `469.731,977 ms` para `5.901,082 ms`,
  a média de `1.008,009 ms` para `76,637 ms` e o máximo de `44.900,790 ms`
  para `308,782 ms`;
- o throughput PACS.002 ativo subiu de `119,600/s` para `242,983/s`;
- a latência externa p50 caiu de `70.284,359 ms` para `21.462,439 ms`, e a
  p95 de `80.436,767 ms` para `75.396,138 ms`;
- a persistência PACS.008 alcançou todos os `135.000` originais, contra
  `80.905` rows no controle, enquanto seu custo acumulado caiu de
  `251.241,847 ms` para `102.427,332 ms`;
- os `135.000/135.000` originais e `7.829/7.829` replays receberam HTTP 2xx,
  sem violações de replay ou outcomes contraditórios.

A variante permanece **mantida**. Ela remove a fragmentação acidental dos
batches de status sem reduzir a concorrência PACS.008 nem a workload externa.
O PostgreSQL continuou saturado em aproximadamente um core (`103,596%` de CPU
média), mas o settlement PACS.002 deixou de liderar seu tempo acumulado. As
maiores queries passaram a ser a admissão PACS.008 (`102.427,332 ms`), o claim
de `notification_delivery` (`94.391,226 ms`) e a publicação da outbox
(`72.392,540 ms`).

O run ainda não qualifica o SLA final: o menor rolling foi `1.924 TPS`, apenas
`13.505` outcomes foram observados e a latência permaneceu acima do limite. Uma
checagem posterior encontrou os grupos Kafka quiescentes, o que comprova
consumo dos offsets, não conclusão de outbox ou entrega. O próximo experimento
deve escolher uma única fronteira entre admissão PACS.008 e publicação/entrega
de notificações com base na correlação temporal existente. Nenhum run de 15
minutos foi executado.

## Persistência set-based de `notification_delivery`

O experimento seguinte alterou somente a persistência Kafka do
notification-gateway. O `NamedParameterJdbcTemplate.batchUpdate`, que aparecia
no PostgreSQL como um `INSERT` por notificação, foi substituído por um único
`INSERT ... SELECT FROM unnest(...) ON CONFLICT DO NOTHING` por poll. Claim,
lease, dispatch e ACK permaneceram inalterados.

A preparação subiu a stack, mas seu primeiro smoke parou antes da workload por
timeout de um dos 100 prewarms HTTP/2. Sobre a stack aquecida, o smoke
`notification-delivery-batch-smoke/20260818_210633` completou
`1.250/1.250` originais, `1.000/1.000` PACS.002 e `112/112` replays, com todos
os outcomes corretos. A única invalidez foi o piso rolling do gerador no perfil
curto.

O diagnóstico `notification-delivery-batch/20260818_210845` foi comparado com
`status-concurrency-one/20260818_204528`:

- as execuções de `INSERT INTO notification_delivery` caíram de `118.692` para
  `629`, e o tamanho médio subiu de `1` para `115,254` rows por chamada;
- o tempo acumulado desses INSERTs caiu de `90.686,636 ms` para
  `66.791,363 ms`, mas as rows persistidas também variaram de `118.692` para
  `72.495`; portanto o delta bruto não prova redução de custo por row;
- os claims concluídos subiram de `45.713` para `69.301` deliveries, as
  notificações recebidas pelo load-tool de `45.713` para `68.736` e os outcomes
  correspondentes de `13.505` para `20.544`;
- o PostgreSQL permaneceu saturado em um core, com CPU média praticamente
  invariável (`103,596%` para `103,237%`);
- todos os `134.998/134.998` originais iniciados e `8.224/8.224` replays
  receberam HTTP 2xx, sem outcomes contraditórios nem violações de replay;
- o piso rolling piorou de `1.924` para `1.163 TPS`, enquanto a média ativa
  ficou em `2.084,617 TPS` e o pico em `3.877 TPS`; a forma da carga ativa não
  foi equivalente ao controle e não comprova o piso sustentado;
- a latência HTTP PACS.008 p95/p99 subiu de `44,367 / 85,535 ms` para
  `271,013 / 402,151 ms`, ainda sem respostas não-2xx;
- uma única transição PACS.002 apresentou máximo de `55.691,801 ms`; o total
  dessa query subiu para `59.548,963 ms` e apenas `18.449` rows transicionaram,
  impedindo atribuir toda a diferença end-to-end ao INSERT do gateway.

A mudança set-based permanece porque remove `99,47%` das execuções SQL dessa
fronteira e aumentou em aproximadamente `50%` o trabalho útil entregue, sem
alterar as invariantes funcionais. Ela não qualifica o SLA global: o ganho
deslocou pressão para outras queries do mesmo PostgreSQL. O lag Kafka imediato
foi `0 / 0 / 869` para pagamento, status e gateway e chegou posteriormente a
zero; isso não prova conclusão de outbox ou delivery. Nenhum run de 15 minutos
foi executado.

### Repetição limpa e gargalo remanescente

A repetição `notification-delivery-batch-repeat/20260818_212030` partiu de
volumes novos e teve preparação e smoke qualificados na primeira tentativa. A
geração ativa foi comparável ao controle: `120.000` originais, média exata de
`2.000 TPS`, mínimo rolling de `1.958 TPS` e máximo de `2.056 TPS`. Todos os
`135.000/135.000` originais e `8.440/8.440` replays iniciados receberam HTTP
2xx, sem outcomes contraditórios nem violações de replay.

O outlier PACS.002 não era ruído. A transição `ACCEPTED` voltou a ocupar quase
um minuto: máximo de `59.199,949 ms`, `86.519,779 ms` acumulados, apenas `19`
chamadas e `2.536` rows transicionadas. A rejeição de saldo insuficiente também
foi privada de capacidade: `171.248,040 ms` acumulados e apenas `11.876` das
`27.000` rows esperadas. A amostragem encontrou essas queries ativas sem wait
event em `169` e `283` amostras, respectivamente; portanto a evidência não
caracteriza espera de row lock, mas competição por execução no PostgreSQL já
saturado em um core (`103,919%` de CPU média).

O claim do gateway foi o consumidor pesado mais estável nos três diagnósticos:

- controle: `158` chamadas, `45.713` rows e `94.391,226 ms`;
- primeira variante: `408` chamadas, `69.301` rows e `79.946,915 ms`;
- repetição: `350` chamadas, `58.186` rows e `76.941,129 ms`.

Na repetição, o claim tocou `2.177.219` buffers compartilhados e leu `232.597`,
aproximadamente `6.220` hits por chamada. O INSERT set-based permaneceu efetivo:
`550` execuções persistiram `62.186` deliveries, sem voltar ao padrão de um
statement por row.

No deadline havia lag Kafka de `34.738 / 45.401 / 973` para pagamento, status e
gateway. Os offsets chegaram posteriormente a zero, mas, já sem os streams do
load-tool, a tabela do gateway continha `61.436` deliveries `ACKED` e `169.308`
`PENDING`. Isso confirma que quiescência Kafka não equivale a conclusão
end-to-end.

O gargalo remanescente está caracterizado como competição do pipeline inteiro
pelo único core do PostgreSQL. O PACS.002 com um consumer sofre head-of-line
blocking quando sua transação perde capacidade de execução; o claim do gateway
é uma fonte grande, repetível e independente dessa pressão. Nenhum run de 15
minutos está autorizado ainda.

#### Causa da cauda PACS.002

A reconstrução da chamada de `59,2 s` eliminou lock, I/O e plano inadequado como
causas principais. O mesmo PID permaneceu por `58,8 s` na transição, sempre sem
blocking PID e com apenas quatro amostras pontuais de `DataFileRead` ou
`DataFileWrite`. Nas `19` execuções agregadas, o PostgreSQL registrou somente
`373,635 ms` de leitura e `2,143 ms` de escrita. O plano genérico usa
`Function Scan` no `unnest` seguido de `Index Scan` pela chave primária de
`payment_transaction_entity`; não há sequential scan nem trigger.

O JFR confirma que o único consumer PACS.002 permaneceu aguardando a resposta
do PostgreSQL em `IncomingStatusReportPersistence.acquireTransitions`. Das
`19` chamadas agregadas, dez socket reads acima do limiar do JFR foram capturados em
`70,6 ms`, `73,2 ms`, `81,3 ms`, `157 ms`, `251 ms`, `1,11 s`, `3,07 s`,
`3,51 s`, `15,3 s` e `50,8 s`. Esses eventos não permitem calcular p95/p99 por
statement, pois uma chamada pode realizar mais de um socket read e leituras
curtas podem ficar abaixo do limiar do JFR. Eles permitem concluir que não foi
apenas uma espera extrema: o consumer sofreu várias esperas JDBC de segundos,
incluindo duas acima de quinze segundos.

Durante os `59,2 s`, havia em média `9,33` backends ativos, dos quais `6,08`
sem wait event, com pico de dez backends executáveis concorrendo pelo único
core. A maior concorrência veio das rejeições insufficient-funds, com média de
`2,04` execuções simultâneas no intervalo, seguida por ACK, claim, publicação
da outbox, lookup de conflitos, admissão PACS.008, auditoria e persistência de
delivery. A evidência caracteriza fila de CPU/over-concurrency no PostgreSQL,
não uma query PACS.002 intrinsecamente lenta.

Antes de otimizar o claim isoladamente, o próximo A/B deve testar redução da
concorrência transacional que chega ao único core, preservando taxa externa e
recursos. A hipótese mais direta é aplicar ao consumer PACS.008 o mesmo
princípio já validado para PACS.002: menos consumers, batches maiores e menos
transações simultâneas. O resultado deve ser avaliado por throughput
end-to-end, batches, backends ativos e cauda PACS.002; reduzir trabalho aceito
ou a workload não é permitido.

## Primeira medição PACS.008 com um consumer

O diagnóstico `pacs008-concurrency-one/20260818_231033` alterou somente a
concorrência do listener PACS.008 de oito para um. PACS.002 permaneceu com um
consumer, `max.poll.records` permaneceu em 500, o PostgreSQL permaneceu com um
vCPU e a workload externa continuou em 2.000 pagamentos originais por segundo.
O ambiente foi recriado com volumes novos e o smoke de preparação qualificou
`1.250/1.250` originais, `1.000/1.000` PACS.002 e `112/112` replays.

Na execução medida, todos os `135.000/135.000` originais e `8.283/8.283`
replays iniciados receberam HTTP 2xx. A redução de concorrência produziu os
efeitos esperados dentro do PostgreSQL:

- a admissão PACS.008 caiu de `1.963` para `237` chamadas e o batch médio
  subiu de `48,893` para `352,903` rows;
- as rejeições insufficient-funds caíram de `961` para `187` chamadas e o
  batch médio subiu de `12,358` para `95,401` rows;
- o tempo acumulado das rejeições caiu de `171.248,040 ms` para
  `2.317,346 ms`, e o máximo de `6.902,461 ms` para `107,774 ms`;
- a transição PACS.002 processou `36.625` rows em `11.575,256 ms`, contra
  `2.536` rows em `86.519,779 ms`; seu máximo caiu de `59.199,949 ms` para
  `4.285,863 ms`;
- na janela ativa, a média de backends ativos caiu de `10,80` para `5,13`, e
  a de backends ativos sem wait event de `6,79` para `4,11`;
- a CPU do PostgreSQL permaneceu saturada e praticamente igual,
  `103,919%` contra `103,301%`, mas realizou mais trabalho downstream;
- outcomes observados até o deadline subiram de `9.403` para `40.778`, e o
  gateway entregou `108.550` notificações ao load-tool, contra `58.186` no
  controle.

A medição também expôs o custo da serialização. Até o deadline, a admissão
persistiu `83.638` pagamentos, contra `95.977` com oito consumers. O ingresso
HTTP permaneceu correto, mas sua p95/p99 subiu de `46,166 / 94,183 ms` para
`297,807 / 487,756 ms`. A geração ativa apresentou catch-up: média
`2.077,583 TPS`, mínimo rolling de `486 TPS` e máximo de `3.949 TPS`, contra
`2.000 / 1.958 / 2.056 TPS` no controle. Portanto, a forma temporal não foi
equivalente e esta primeira medição não decide sozinha a configuração final.

O resultado confirma a hipótese de over-concurrency: batches maiores removem
convoy de CPU e aumentam fortemente a conclusão end-to-end, sem aliviar o
PostgreSQL por redução de carga oferecida. Ainda assim, o relatório permanece
inválido, com `94.222` outcomes ausentes e latência externa p95 de
`64.790,617 ms`. Uma checagem posterior encontrou os três consumer groups com
lag zero, o que comprova drenagem dos offsets, não conclusão dentro do SLA. A
variante só deve ser mantida se preservar o ganho de batches/cauda em uma
repetição com perfil temporal comparável. Nenhum run de 15 minutos foi
executado.

A tentativa aquecida
`pacs008-concurrency-one-repeat/20260818_231809` foi descartada antes do report.
Embora os consumer groups estivessem com lag zero antes do início, o gateway
ainda possuía deliveries persistidas da execução anterior. O novo load-tool,
cujos IDs começavam em `go-1787105914397003675`, recebeu uma PACS.008 antiga
para `go-1787105461027121708-41113` e abortou corretamente porque não havia
metadados desse pagamento no run corrente. A tentativa aceitou
`134.999/134.999` originais e `6.749/6.749` replays antes de detectar a mistura,
mas não constitui benchmark e não produziu `sla-report.json`.

Essa falha confirma concretamente que lag Kafka zero não caracteriza ambiente
end-to-end quiescente: outbox e `notification_delivery` podem continuar
pendentes após os offsets terem sido consumidos. Com o isolamento atual, uma
repetição válida exige ambiente novo; simplesmente executar outra vez sobre a
mesma stack empilharia o backlog do run descartado. Automatizar uma fronteira
de quiescência/limpeza end-to-end fica separado da decisão de performance desta
variante.

### Repetição limpa e decisão sobre um consumer

A stack foi recriada novamente e o smoke qualificou na primeira tentativa. A
repetição `pacs008-concurrency-one-clean-repeat/20260818_232657` confirmou os
dois lados da primeira medição:

- `135.000/135.000` pagamentos originais e `8.085/8.085` replays receberam
  HTTP 2xx;
- o batch médio da admissão PACS.008 permaneceu grande, com `301,053` rows por
  chamada, e o de insufficient-funds com `89,291` rows por chamada;
- a transição PACS.002 processou `37.335` rows, batch médio de `377,121`, tempo
  acumulado de `64.607,189 ms` e máximo de `8.414,778 ms`; apesar da variação
  contra a primeira tentativa, a cauda continuou muito abaixo dos
  `59.199,949 ms` do controle;
- outcomes observados ficaram em `33.866`, contra `9.403` com oito consumers,
  e o load-tool recebeu `97.987` notificações, contra `58.186`;
- o PostgreSQL permaneceu saturado (`104,067%` de CPU média), enquanto os
  backends ativos médios caíram de `10,80` para `2,95` e os executáveis sem
  wait event de `6,79` para `2,11`.

O custo também se repetiu. Apenas `62.920` pagamentos foram admitidos até o
deadline, contra `95.977` no controle e `83.638` na primeira variante. A
geração voltou a apresentar catch-up, agora com média/mínimo/máximo rolling de
`2.069,2 / 1.160 / 4.158 TPS`; a latência HTTP PACS.008 p95/p99 ficou em
`196,542 / 300,766 ms`, ainda muito acima dos `46,166 / 94,183 ms` do controle.
Os offsets Kafka chegaram posteriormente a zero, fora do experimento.

Um consumer ainda não está aprovado como configuração final, pois não comprovou
processamento end-to-end sustentado de 2.000 pagamentos por segundo. Ele passa,
porém, a ser a baseline deliberada da próxima etapa de otimização: serializa a
admissão PACS.008, forma batches substanciais e reduz o convoy que escondia o
custo do trabalho executado no PostgreSQL.

A próxima etapa não aumenta a concorrência para dois. Ela mantém PACS.008 e
PACS.002 com um consumer, PostgreSQL com um vCPU e a workload externa inalterada
enquanto reduz o custo por pagamento do pipeline persistente. Cada intervenção
deve escolher uma única fronteira a partir de chamadas, rows, buffers, WAL,
tempo por batch, outcomes concluídos e crescimento do backlog. Tempo acumulado
sob saturação não basta sozinho para atribuir custo de CPU, pois uma query pode
ser vítima da fila de execução.

O progresso esperado ocorre nesta ordem:

1. com backlog ainda presente e PostgreSQL próximo de 100%, aumentar rows e
   outcomes concluídos por segundo e reduzir custo por pagamento;
2. reduzir a inclinação do backlog até a capacidade alcançar a carga oferecida;
3. observar folga de CPU somente quando a demanda deixar de manter trabalho
   permanentemente pronto;
4. aumentar a concorrência PACS.008 apenas se houver folga no PostgreSQL e o
   listener único ainda acumular lag ou limitar a latência;
5. manter um consumer se ele sustentar 2.000 pagamentos originais por segundo,
   replays e outcomes dentro do SLA.

Assim, CPU saturada com backlog continua significando “otimizar custo”, não
“adicionar consumers”. Nenhuma nova repetição sem uma intervenção de custo e
nenhum run de 15 minutos estão autorizados.

## Otimização do claim de notificações

A intervenção `notification-claim-due-index/20260819_011815` manteve
PACS.008/PACS.002 com um consumer, PostgreSQL com um vCPU, batch de claim em
1.000, polling em 20 ms e o workload diagnóstico inalterado. O contrato de
lease foi simplificado: `next_attempt_at` passou a representar também a
expiração de `IN_FLIGHT`, `lease_until` foi removido e dois índices parciais
passaram a conter somente `PENDING`, `RETRYABLE_FAILED` e `IN_FLIGHT`.

O primeiro smoke de preparação terminou antes de gerar pagamentos porque um
dos 100 health checks HTTP/2 excedeu cinco segundos enquanto o front door
processava os handshakes em um único core. A checagem posterior encontrou zero
payments, zero deliveries e Kafka quiescente. Na mesma stack limpa, já
aquecida, a segunda tentativa qualificou `1.250/1.250` originais,
`1.000/1.000` PACS.002 e `112/112` replays. Nenhum benchmark foi contado a
partir da tentativa abortada.

Contra `pacs008-concurrency-one-clean-repeat/20260818_232657`, o claim mudou
assim:

- chamadas: `953 -> 525`;
- rows adquiridas: `98.987 -> 160.231`, com batch médio
  `103,87 -> 305,20`;
- tempo SQL total: `74.774,780 -> 73.029,230 ms`, ou
  `0,755 -> 0,456 ms` por row;
- máximo por chamada: `5.165,934 -> 1.005,805 ms`;
- buffer hits por row: `42,53 -> 17,66`;
- reads por row: `0,416 -> 0,088`;
- WAL por row: `1.162 -> 1.118 bytes`.

O `EXPLAIN` posterior escolheu `notification_delivery_claim_due_idx`, ordenado
por `next_attempt_at`, seguido apenas de incremental sort para desempatar por
`communication_id`. Portanto o claim deixou de ordenar o backlog completo. O
índice por `(recipient_ispb, next_attempt_at)` permanece disponível para a
forma complementar com poucos PSPs conectados.

O ganho local também aumentou trabalho útil até o deadline:

- pagamentos admitidos: `62.920 -> 81.221`;
- outcomes observados: `33.866 -> 58.368`;
- notificações recebidas pelo load-tool: `97.987 -> 159.231`;
- latência HTTP PACS.008 p95/p99: `196,539 / 300,616 ms ->
  167,515 / 283,825 ms`;
- geração média/mínima/máxima: `2.069,2 / 1.160 / 4.158 TPS ->
  2.026,283 / 1.736 / 2.984 TPS`.

Todos os `135.000/135.000` originais e `8.602/8.602` replays iniciados
receberam HTTP 2xx; não houve outcome contraditório nem violação de replay. O
run continua inválido: faltaram `76.632` outcomes, o mínimo rolling ficou em
`1.736 TPS` e a latência end-to-end permaneceu muito acima do SLA.

O PostgreSQL continuou sendo o recurso limitante, com `100,386%` de CPU média
na janela ativa. Backends ativos/executáveis subiram de `2,95 / 2,11` para
`4,95 / 3,75`, coerente com mais trabalho downstream alcançando o mesmo core;
isso não anula a redução substancial do custo do claim por row. Uma leitura
posterior encontrou lag Kafka zero, mas ainda havia `88.361` deliveries
`PENDING`, novamente confirmando que offsets drenados não representam
conclusão end-to-end.

A consulta e os índices simplificados foram mantidos porque reduzem trabalho
por notificação, aumentam outcomes e melhoram o ingresso sem regressão funcional.
Não alterar polling ou batch size neste mesmo experimento. O próximo passo deve
escolher outra fronteira dominante do PostgreSQL a partir do novo perfil; nenhum
run de 15 minutos está autorizado ainda.

## Persistência de notificações com um consumer

A intervenção `notification-persistence-concurrency-one/20260819_013847`
reduziu somente a concorrência Kafka do notification-gateway de dois consumers
para um. O workload, os recursos, o batch máximo, o SPI e as demais
configurações permaneceram inalterados.

Contra `notification-claim-due-index/20260819_011815`, a persistência em
`notification_delivery` mudou assim:

- chamadas: `1.634 -> 722`;
- rows por chamada: `109,442 -> 214,443`;
- tempo por row: `0,542 -> 0,215 ms`;
- máximo por chamada: `590,392 -> 285,224 ms`;
- CPU média do notification-gateway: `24,980% -> 13,571%`.

O PostgreSQL permaneceu saturado em um core (`103,565%` de CPU média). A
quantidade de pagamentos admitidos subiu de `81.221` para `96.340`, mas o
trabalho adicional avançou até a fronteira PACS.002 e o número de outcomes
observados caiu de `58.368` para `43.848`. Isso não caracteriza regressão do
insert local: a variante removeu execuções e reduziu aproximadamente 60% do
custo SQL por delivery, enquanto expôs a próxima fronteira limitada pelo mesmo
core.

Todos os `134.999/134.999` originais e `8.574/8.574` replays receberam HTTP
2xx, sem outcome contraditório nem violação de replay. O run permaneceu
inválido, com mínimo rolling de `1.967 TPS`, `91.151` outcomes ausentes e
latência end-to-end p95/p99 de `84.295,718 / 86.584,256 ms`.

Um consumer foi mantido porque forma lotes maiores e reduz o custo da
persistência de delivery sem limitar o ingresso. Voltar a dois consumers apenas
reduziria artificialmente o trabalho que alcança as etapas posteriores.

## Diagnóstico da transição PACS.002 ACCEPTED

No mesmo run, a transição
`WAITING_ACCEPTANCE -> ACCEPTED_AND_SETTLED` foi a consulta individual mais
cara do PostgreSQL:

- `91` chamadas e `31.366` rows, batch médio de `344,681`;
- `79.515,158 ms` acumulados, média de `873,793 ms` e máximo de
  `18.673,779 ms`;
- apenas `385,099 ms` de leitura física e `10,567 ms` de escrita física;
- `220` amostras ativas sem wait event, nenhuma com blocking PID, e uma amostra
  em `ClientRead`;
- nos instantes em que a query estava ativa havia em média `2,534` backends
  ativos e `1,991` executáveis sem wait event, concorrendo por um container
  limitado a um vCPU.

A leitura e o lock prévios dos mesmos pagamentos também ocorreram `91` vezes,
mas acumularam somente `3.507,201 ms`, média de `38,541 ms`. Portanto essa etapa
não explica os `79,5 s` atribuídos ao update.

Para separar custo intrínseco de espera por CPU, o update foi reproduzido com
`350` pagamentos `WAITING_ACCEPTANCE` enquanto o ambiente estava quiescente,
dentro de `BEGIN`/`ROLLBACK`. O `EXPLAIN (ANALYZE, BUFFERS, WAL)` concluiu em
`43,574 ms`; aproximadamente `39 ms` pertenciam exclusivamente à seleção
artificial dos fixtures. O `Function Scan` sobre `unnest`, os `350` index scans
pela chave primária e o update consumiram aproximadamente `4 ms`. Não houve
sequential scan no caminho real do update.

O teste quiescente gerou `392` registros de WAL e `203.305` bytes, incluindo
`29` full-page images, mas esse volume não produziu latência relevante. As
estatísticas acumuladas da tabela mostram `96.726` updates, dos quais `37.981`
foram HOT (`39,27%`), zero tuplas mortas após dois autovacuums, heap de `33 MB`
e apenas o índice primário, com aproximadamente `9,7 MB`. A proporção de HOT
updates pode representar amplificação secundária, mas não explica uma operação
que custa poucos milissegundos sem competição.

**Conclusão diagnóstica:** o update PACS.002 não é intrinsecamente lento com o
batch observado. Seu tempo sob workload é tempo de parede acumulado enquanto o
backend permanece executável e disputa o único core do PostgreSQL com as demais
transações. O PACS.002 é a principal vítima individual dessa fila de CPU, não
uma evidência de plano ruim, row lock, storage I/O ou bloat dominante. O bundle
de 60 segundos já continha statements, activity, I/O, CPU e JFR; por isso não
foi executado outro workload idêntico somente para repetir a mesma coleta.

### CPU real por família SQL

O diagnóstico `pacs002-executor-cpu-diagnostic/20260819_125200` ativou
temporariamente o `log_executor_stats` nativo do PostgreSQL, executou uma única
carga de 60 segundos e restaurou a configuração para `off` imediatamente após
o run. O primeiro smoke de preparação expirou durante o prewarm HTTP/2, antes
de gerar tráfego. Na mesma stack limpa e já aquecida, o smoke manual qualificou
`1.250/1.250` originais, todos os outcomes e `112/112` replays.

O profiler produziu `7.898` entradas e aproximadamente `41 MB` de log. Queries
`UPDATE ... RETURNING` emitiram um registro para o executor interno e outro
registro externo cumulativo. A agregação preservou somente o registro externo;
como controle, as `1.000` entradas de ACK resultaram exatamente nas `500`
chamadas registradas pelo `pg_stat_statements`.

Na janela ativa, `1.977` execuções deduplicadas atribuíram `42,750 s` de CPU de
executor (`user + system`):

| família SQL | CPU | participação da CPU medida |
| --- | ---: | ---: |
| persistência em `notification_delivery` | `7,575 s` | `17,72%` |
| claim de `notification_delivery` | `7,350 s` | `17,19%` |
| insert da notification outbox | `7,200 s` | `16,84%` |
| insert PACS.008 | `4,905 s` | `11,47%` |
| ACK de delivery | `4,310 s` | `10,08%` |
| publicação da notification outbox | `3,970 s` | `9,29%` |
| auditoria de pagamentos | `2,974 s` | `6,96%` |
| lock/leitura PACS.002 | `1,531 s` | `3,58%` |
| poll da notification outbox | `1,392 s` | `3,26%` |
| update da transição PACS.002 | `1,228 s` | `2,87%` |

O ciclo de outbox/delivery somou `31,796 s`, ou `74,38%` da CPU atribuída aos
executores. Já lock, transição e operações de saldo diretamente associadas ao
PACS.002 somaram `2,836 s`, ou `6,63%`. Auditoria e outbox são compartilhadas
por diferentes resultados de negócio e não podem ser atribuídas integralmente
ao accepted apenas pelo texto SQL.

O update PACS.002 consumiu em média `23,173 ms` de CPU e `119,914 ms` de tempo
transcorrido por chamada. Portanto somente aproximadamente um quinto do tempo
observado correspondeu a CPU do próprio executor. As `53` amostras do lock e
as `53` transições da janela preservaram batches grandes; nenhuma amostra da
transição teve blocking PID.

O PostgreSQL permaneceu limitado a um core, com `103,144%` de CPU média. O
profiler não cobre parser, planner, commit, processos de background nem seu
próprio custo de formatação/escrita do log; por isso os `42,750 s` não devem ser
interpretados como toda a CPU do container. Ele também perturbou o experimento:
o log cresceu `41 MB`, havia em média `4,095` backends executáveis, e a geração
rolling variou de `1.119` a `3.744 TPS`. Este run serve somente para atribuição
diagnóstica, não para comparação de throughput ou SLA.

**Conclusão revisada:** o ranking por tempo transcorrido superestimava o papel
do update PACS.002. A transição é afetada pela saturação, mas não é consumidora
dominante de CPU. O trabalho dominante mensurado está distribuído no ciclo de
outbox e delivery de notificações. Qualquer próxima intervenção deve partir da
CPU por família, e não do `total_exec_time` isolado do `pg_stat_statements`.

### Investigação do ciclo de notificações

Uma notificação concluída atravessa hoje cinco mutações PostgreSQL: insert da
outbox, marcação da outbox como publicada, insert da delivery, claim da delivery
e ACK. A agregação de todos os `queryid` preservados — sem o limite de 50 rows do
artefato CSV — caracterizou o trabalho da execução completa assim:

| operação | chamadas | rows | rows/chamada | tempo/row | WAL/row |
| --- | ---: | ---: | ---: | ---: | ---: |
| insert da outbox | `610` | `173.594` | `284,580` | `0,383518 ms` | `1.149 B` |
| publicação da outbox | `337` | `172.115` | `510,727` | `0,237188 ms` | `893 B` |
| insert da delivery | `668` | `171.404` | `256,593` | `0,428209 ms` | `1.124 B` |
| claim da delivery | `552` | `153.439` | `277,969` | `0,510846 ms` | `1.131 B` |
| ACK da delivery | `500` | `151.441` | `302,882` | `0,303146 ms` | `897 B` |

Esses números abrangem warmup, ativo e drain e servem para caracterizar custo
unitário; a participação de CPU continua sendo a medição da janela ativa da
seção anterior. Uma notificação que percorre as cinco etapas gera, na ordem de
grandeza observada, aproximadamente `5,2 KB` de WAL somente nesse ciclo.

Batching pequeno não é a explicação dominante. No insert da outbox, `84%` das
rows estavam em lotes de pelo menos `250`; na publicação eram `96%`. As médias
das demais operações também ficaram entre `256` e `303` rows por chamada.

O custo é amplificado pela representação persistida. As rows amostradas têm em
média `666 B` na outbox e `686 B` na delivery, dos quais aproximadamente
`452-471 B` são o payload imutável. A stack, já após continuar drenando trabalho,
registrava `243.283` inserts e `243.283` updates na outbox, além de `243.283`
inserts e `312.380` updates na delivery, com zero HOT update nas duas tabelas.
Isso é estrutural: a publicação altera o predicado do índice parcial da outbox;
o claim altera `next_attempt_at` nos índices de delivery; e o ACK remove a row
dos mesmos índices. Cada transição, portanto, cria nova versão de heap e trabalho
de índice, carregando junto o payload que não mudou.

Há também uma parcela acidental e menor, mas diretamente atacável. O insert da
outbox constrói `VALUES` dinamicamente, com até `7.000` bind parameters para um
lote de `1.000`. O PostgreSQL reteve `316` formatos de statement para apenas
`610` chamadas; o SQL médio tinha `36.024` caracteres e o máximo `121.203`.
No JFR do SPI, `29` amostras em `AbstractStringBuilder` e `12` no parser JDBC —
`41` das `474` amostras de execução — pertenciam especificamente a
`NotificationOutboxRepository.insertAll`. O insert de delivery já demonstra o
padrão estável equivalente com arrays e `unnest`.

**Hipótese para o próximo experimento:** substituir somente o `VALUES` dinâmico
do insert da outbox por um statement fixo com arrays e `unnest`, preservando a
transação, `ON CONFLICT`, payload e tamanho dos lotes. Essa mudança remove
construção, parsing e planejamento proporcionais ao tamanho do SQL sem alterar
o contrato de durabilidade. Ela deve ser avaliada isoladamente antes de qualquer
mudança estrutural em retenção, payload ou estados de delivery. O run com
`log_executor_stats` não é baseline de performance para esse A/B por causa da
perturbação já documentada.

A intervenção foi implementada no `NotificationOutboxRepository`: lotes de
qualquer tamanho agora usam o mesmo statement e sete arrays JDBC. O teste
focado protege a estabilidade do SQL entre tamanhos de lote e a liberação dos
arrays; os testes com PostgreSQL real preservam bytes, status nulo,
idempotência e rollback.

### A/B do insert estável da notification outbox

O B `outbox-unnest/20260819_140346` foi executado uma única vez, após o
preparador qualificar na primeira tentativa os `1.250/1.250` pagamentos do
smoke, seus `1.000/1.000` PACS.002 e todos os replays. O A comparável é
`notification-persistence-concurrency-one/20260819_013847`; os snapshots de
perfil e execution plan dos dois bundles são byte a byte iguais. A única
mudança no caminho medido foi o insert da outbox com SQL fixo, arrays e
`unnest`.

O mecanismo mudou conforme a hipótese:

- o snapshot B contém um único `queryid` para o insert, com `811` chamadas e
  `216.025` rows; o top 50 do A já continha pelo menos `20` variantes, embora
  não permita reconstruir todas as variantes dinâmicas daquele run;
- no JFR comparável, as amostras que continham
  `NotificationOutboxRepository.insertAll` caíram de `40/202` para `11/344`
  amostras de execução (`19,80% -> 3,20%`);
- dentro desse caminho, as `10` amostras de `AbstractStringBuilder` e as `6`
  do parser JDBC observadas no A caíram a zero no B;
- o PostgreSQL continuou saturado no mesmo nível durante a janela ativa
  (`103,565% -> 103,293%` de CPU média). A intervenção reduz custo por trabalho,
  mas ainda não cria folga porque a stack usa a capacidade liberada para
  avançar mais notificações.

Esse avanço apareceu no resultado end-to-end:

| Evidência | A | B |
| --- | ---: | ---: |
| pagamentos originais aceitos | `134.999` | `135.000` |
| outcomes observados até o deadline | `43.848` | `62.940` |
| outcomes ausentes | `91.151` | `72.060` |
| payer notifications/s no ativo | `125,933` | `472,900` |
| latência end-to-end p95 | `84.295,718 ms` | `63.706,364 ms` |
| latência end-to-end p99 | `86.584,256 ms` | `67.485,210 ms` |

Os outcomes cresceram `43,54%` e o p95 caiu `24,43%`. O trabalho adicional
também elevou a CPU média de SPI e gateway; isso é coerente com mais mensagens
atravessando o pipeline, não com a transferência do custo removido para esses
componentes.

O ganho por cenário não foi uniforme: happy-path passou de `25.154` para
`46.371` outcomes, enquanto insufficient-funds caiu de `18.694` para `16.569`
antes do deadline. O teste não oferece fairness temporal por cenário e ambos
compartilham o mesmo ciclo de notificação saturado. Essa redistribuição deve ser
preservada como ressalva do A/B, mas não constitui regressão semântica: não
houve outcome contraditório, os `135.000/135.000` originais receberam HTTP 2xx
e os replays PACS.008 e PACS.002 tiveram zero violações. A checagem posterior
encontrou Kafka quiescente.

Os dois runs permanecem inválidos para aprovação: o B teve mínimo rolling de
`1.960 TPS` e ainda encerrou com `72.060` outcomes ausentes e latência muito
acima do SLA. Portanto esta mudança não encerra a estabilização nem autoriza o
run de 15 minutos.

O statement estável foi mantido porque remove a construção e o parsing
proporcionais ao tamanho do lote, preserva o contrato funcional e produz ganho
end-to-end mensurável sem reduzir a carga original admitida. O próximo trabalho
deve partir novamente do perfil dominante do PostgreSQL após esta intervenção,
em vez de continuar micro-otimizando o mesmo insert.

### CPU do ciclo de notificações após o `unnest`

O diagnóstico `notification-cycle-cpu-post-unnest/20260819_152958` repetiu a
atribuição nativa de CPU depois da mudança do insert da outbox. A stack foi
recriada e o smoke qualificou na primeira tentativa, com `1.250/1.250`
pagamentos, `1.000/1.000` PACS.002 e `112/112` replays. O
`log_executor_stats` permaneceu ativo somente durante uma execução de 60
segundos e foi restaurado para `off`; `pg_stat_statements.track` também foi
restaurado para `none`.

Registros internos e externos dos statements com `RETURNING` foram
deduplicados, preservando apenas a execução externa. Na janela ativa, `2.141`
execuções atribuíram `42,006 s` de CPU de executor. O ciclo de notificações
consumiu `32,110 s`, ou `76,44%` dessa CPU:

| operação | CPU de executor | participação |
| --- | ---: | ---: |
| insert de `notification_delivery` | `7,696 s` | `18,32%` |
| insert da notification outbox | `7,549 s` | `17,97%` |
| claim de `notification_delivery` | `7,260 s` | `17,28%` |
| ACK de delivery | `4,422 s` | `10,53%` |
| marcação da outbox como publicada | `4,077 s` | `9,70%` |
| poll da outbox | `1,106 s` | `2,63%` |

O PostgreSQL permaneceu saturado, com `103,719%` de CPU média. Havia em média
`4,611` backends ativos e `3,420` executáveis. O profiler adicionou I/O de log e
perturbou a execução; por isso este run serve somente para atribuição, não como
baseline de throughput ou SLA. Ainda assim, todos os `134.999/134.999`
pagamentos iniciados receberam HTTP 2xx, não houve violações de replay nem
outcomes contraditórios, e `63.669` outcomes foram observados até o deadline.

O custo está distribuído pelo protocolo persistente, não concentrado em uma
query isolada. Cada notificação mantém uma row de aproximadamente `650 B` na
outbox e outra de `660 B` na delivery, com payload imutável de cerca de
`430-440 B` em ambas. Insert da outbox, marcação como publicada, insert da
delivery, claim e ACK geraram aproximadamente `5,4 KB` de WAL por notificação.
As duas tabelas continuaram com zero HOT updates porque as transições alteram
os índices parciais.

A mudança anterior não pretendia reduzir a CPU do executor do insert: ela
removeu construção de strings e parsing/planejamento, custos externos ao
executor medido. O perfil atual confirma que o insert deixou de ter formatos
dinâmicos, mas encontrou uma duplicação do mesmo mecanismo em
`markPublished`: `393` chamadas atualizaram `224.082` rows usando `180`
formatos de statement diferentes, acumularam `39.889,648 ms` de execução e
`201.389.584` bytes de WAL.

**Próxima hipótese:** substituir somente o `IN (?, ...)` dinâmico de
`markPublished` por um statement estável com array/`unnest`, preservando estado,
idempotência e transação. Esse A/B deve medir redução de formatos SQL,
parser/planejador e custo end-to-end. Mesmo se mantida, a mudança remove apenas
complexidade acidental; a sequência de cinco mutações e a duplicação do payload
continuam sendo o limite estrutural a reavaliar depois.

### Fast path persistido da entrega de notificações

A hipótese de `markPublished` foi adiada após priorização por ROI. No diagnóstico
anterior, todas as `177.705` notificações reclamadas haviam sido tentadas apenas
uma vez: o claim estava no caminho obrigatório da primeira entrega, e não
recuperando falhas. A intervenção removeu essa transição do caminho saudável sem
remover a recuperação durável.

O consumer Kafka agora obtém um snapshot dos PSPs conectados e faz um único
insert transacional. Linhas novas para PSPs conectados entram como `IN_FLIGHT`,
com tentativa e lease já registrados, e somente elas são devolvidas para envio
após o commit. PSPs desconectados continuam entrando como `PENDING`; conflito em
`communication_id` não devolve row nem cria novo dispatch. O payload enviado é
o mesmo já presente no poll Kafka e não é relido do PostgreSQL.

O envio foi isolado em um dispatcher compartilhado pelo fast path e pela
recuperação. Ele preserva processamento sequencial dentro de cada destinatário,
mantém o executor existente de oito threads e converte ausência de subscriber ou
rejeição do executor em `RETRYABLE_FAILED`. O worker anterior ficou restrito a
`PENDING`, falhas retryable e leases expirados. Seu polling no Compose passou de
`20 ms` para `1 s`; lease de `30 s` e retry de `1 s` foram preservados. Nenhuma
migração de banco foi necessária.

Os testes cobrem lote misto conectado/desconectado, deduplicação, rollback,
dispatch posterior à persistência, falha de envio, rejeição do executor,
concorrência por destinatário e recuperação depois do lease. Uma mutation check
que tornou o lease imediatamente elegível falhou no teste esperado. A suíte
completa do notification-gateway passou com `75` testes.

### A/B do fast path de notification delivery

O preparador recriou a stack e qualificou o smoke na primeira tentativa:
`1.250/1.250` pagamentos, `1.000/1.000` PACS.002 e `112/112` replays. O B
`notification-direct-fast-path/20260819_163347` foi executado uma única vez e
comparado com o A `outbox-unnest/20260819_140346`. Perfil e execution plan são
byte a byte idênticos.

O mecanismo esperado foi observado no run completo:

| Evidência PostgreSQL | A | B |
| --- | ---: | ---: |
| chamadas do claim | `456` | `101` |
| tempo SQL acumulado do claim | `81.592,684 ms` | `4.868,774 ms` |
| WAL do claim | `224.429.886 B` | `5.853.480 B` |
| rows inseridas em delivery | `214.616` | `227.299` |
| custo do insert de delivery por row | `0,359237 ms` | `0,304287 ms` |

As chamadas do claim caíram `77,85%`, seu tempo acumulado caiu `94,03%` e seu
WAL caiu `97,39%`. As `101` chamadas restantes correspondem ao polling de
recovery de um segundo. O `pg_stat_statements` registrou zero rows devolvidas
pelo claim, embora tentativas de lock/recuperação ainda tenham gerado o pequeno
WAL residual. O fast path processou mais deliveries e, mesmo registrando lease
e tentativa no insert, reduziu o custo por row em `15,30%` por deixar de
concorrer com o claim obrigatório.

O PostgreSQL permaneceu saturado (`103,293% -> 103,172%` de CPU média ativa),
mas converteu a capacidade liberada em mais trabalho útil:

| Evidência end-to-end | A | B |
| --- | ---: | ---: |
| originais aceitos | `135.000` | `134.998` |
| outcomes observados | `62.940` | `84.826` |
| outcomes ausentes | `72.060` | `50.172` |
| payer notifications/s no ativo | `472,900` | `696,617` |
| latência end-to-end p95 | `63.706,364 ms` | `56.429,372 ms` |
| latência end-to-end p99 | `67.485,210 ms` | `62.882,518 ms` |

Os outcomes cresceram `34,77%`, os ausentes caíram `30,37%` e o p95 caiu
`11,42%`. Todos os `134.998` originais iniciados receberam HTTP 2xx; não houve
outcome contraditório nem violação de replay, e Kafka estava quiescente na
checagem posterior.

O B continua inválido para aprovação. O gerador iniciou `124.966` originais na
janela ativa, com média `2.082,767 TPS`, mínimo rolling de `820` e pico de
`4.895`; portanto houve catch-up temporal e o piso sustentado não foi provado.
Essa diferença impede usar o run como validação final, mas não contradiz o A/B:
o total original foi equivalente, toda tentativa foi aceita e a variante B
produziu mais outcomes sob uma carga ativa mais concentrada.

No fechamento dos streams, `500` deliveries de quatro PSPs foram marcadas como
retryable porque o dispatch encontrou o subscriber já removido. Os logs não
mostram desconexão desses PSPs antes do deadline; as rows permaneceram duráveis
e recuperáveis, conforme o contrato at-least-once.

A primeira entrega sem polling/claim foi mantida porque o custo esperado
desapareceu e houve ganho end-to-end sem regressão semântica. O próximo
diagnóstico deve reordenar o trabalho PostgreSQL remanescente; `markPublished`
continua como cleanup possível, mas não foi incluído nesta intervenção.

### CPU real após o fast path de delivery

O diagnóstico `notification-fast-path-executor-cpu/20260819_175110` repetiu a
atribuição nativa de CPU no estado já contendo o fast path. A stack foi
recriada, e o smoke qualificou na primeira tentativa com `1.250/1.250`
pagamentos, `1.000/1.000` PACS.002 e `112/112` replays. O
`log_executor_stats` permaneceu ativo somente durante uma execução de 60
segundos; ao final, ele foi restaurado para `off`, e
`pg_stat_statements.track` foi confirmado como `none`. Kafka também ficou
quiescente depois do diagnóstico.

A análise usou a janela ativa semiaberta registrada no `run-window.json`.
Registros internos e externos de statements com `RETURNING` foram
deduplicados pela combinação de PID, SQL, instante de conclusão e uso
cumulativo, preservando o registro externo. Depois de remover `621` registros
internos duplicados, `2.234` execuções atribuíram `43,430 s` de CPU de executor
de aplicação:

| família SQL | CPU de executor | participação |
| --- | ---: | ---: |
| transição PACS.002 | `20,247 s` | `46,62%` |
| ACK de `notification_delivery` | `6,572 s` | `15,13%` |
| insert de `notification_delivery` | `3,718 s` | `8,56%` |
| insert da notification outbox | `3,065 s` | `7,06%` |
| poll da notification outbox | `3,065 s` | `7,06%` |
| publicação da notification outbox | `2,882 s` | `6,64%` |
| insert PACS.008 | `2,150 s` | `4,95%` |
| auditoria de pagamentos | `1,261 s` | `2,90%` |
| claim de recovery | `0,043 s` | `0,10%` |

O claim deixou de ser material. Sem ele, o restante do ciclo persistente de
notificações somou `19,303 s`, ou `44,45%` da CPU medida: praticamente a mesma
ordem da transição PACS.002, mas distribuído entre cinco statements.

A transição PACS.002 foi uma consumidora real de CPU neste run, e não apenas
uma vítima de lock ou I/O. Suas oito execuções concluídas durante a janela ativa
somaram `20,247 s` de CPU e `52,154 s` transcorridos. Nas `183` amostras de
atividade em que o query ID estava ativo, `182` estavam executáveis, nenhuma
tinha blocking PID e apenas uma esperava `DataFileRead`. No snapshot completo,
as `64` chamadas concluídas alteraram `12.299` rows; leitura e escrita físicas
atribuídas ao statement somaram apenas `610,160 ms` e `13,883 ms`. O maior
statement ativo consumiu `5,028 s` de CPU e levou `21,454 s`.

O PostgreSQL permaneceu saturado, com `102,466%` de CPU média e, em média,
`3,011` backends ativos e `2,247` executáveis. Kafka producer (`53,406%`), Kafka
(`17,654%`), gateway (`15,610%`) e SPI (`14,313%`) ficaram abaixo do limite de
CPU. Os executores de aplicação explicam `43,430 s` dos aproximadamente `61,5`
CPU-seconds do container; parser, planner, commit, logging e processos de
background não são atribuídos por essa coleta.

O profiler perturbou fortemente o sistema e este bundle não é baseline de
performance. No fast path sem `log_executor_stats`, a transição PACS.002 havia
processado `74.767` rows em `332` chamadas e `20.697,584 ms` transcorridos. Com
o profiler, processou apenas `12.299` rows em `64` chamadas e
`97.468,658 ms`; o log do servidor cresceu aproximadamente `18,6 MB`. A geração
também não qualificou, com mínimo rolling de `1.925 TPS`. Portanto a coleta
prova quais executores consumiram CPU sob a condição instrumentada, mas não
permite extrapolar diretamente seus throughputs para a execução normal.

**Conclusão diagnóstica:** depois do fast path, não há um único statement de
delivery substituindo o claim como gargalo. A CPU mensurada se divide entre a
transição PACS.002 (`46,62%`) e o restante do ciclo de notificações (`44,45%`).
A transição foi a maior família individual e deve ser investigada como próximo
alvo; o `markPublished` isolado representa somente `6,64%` da CPU de executor e
não deve ser priorizado apenas pelo seu tempo de parede.

### Microbenchmark do update PACS.002 por outcome

O update da transição foi comparado em três formas: o SQL vigente com três
arrays, `unnest` e status por row; status constante por outcome com
`payment_id = ANY(...)` e `RETURNING`; e a mesma forma sem `RETURNING`. O
benchmark preservou a cardinalidade observada de `136.243` pagamentos em três
tabelas temporárias idênticas e atualizou `350` pagamentos
`WAITING_ACCEPTANCE` por amostra. Cada sessão alternou a ordem das variantes,
descartou dez warmups e mediu trinta amostras com rollback.

Uma tentativa preliminar com somente `350` rows nas tabelas scratch foi
descartada: ela induzia sequential scan e não representava o plano da tabela
real. Com a cardinalidade corrigida, o SQL vigente fez nested loop com `350`
index scans pela chave primária. A forma por outcome usou bitmap index scan e
bitmap heap scan. Três sessões independentes, incluindo uma com o
`plan_cache_mode=auto` usado em produção, produziram:

| sessão | SQL vigente | por outcome com retorno | por outcome sem retorno |
| --- | ---: | ---: | ---: |
| genérico 1 | `4,791 ms` | `3,289 ms` (`-31,35%`) | `2,967 ms` (`-38,08%`) |
| genérico 2 | `6,584 ms` | `4,628 ms` (`-29,72%`) | `4,226 ms` (`-35,82%`) |
| auto | `6,335 ms` | `4,444 ms` (`-29,86%`) | `4,078 ms` (`-35,64%`) |

O ganho principal veio da retirada do `unnest`/join e do status variável por
row. Remover o `RETURNING` acrescentou aproximadamente `8-10%` sobre o SQL já
simplificado. O microbenchmark isola o executor e o acesso às rows; não mede o
efeito end-to-end nem reduz por si só a mutação física, WAL ou necessidade de
HOT updates.

A variante experimental agrupou em Java os candidatos pelo status resultante e
executou no máximo um update por outcome. Cada comando exigia que o número de
rows alteradas fosse exatamente o número de candidatos. Somente então as rows
já lidas e bloqueadas pela transação originavam deltas de saldo, auditoria e
outbox; portanto a retirada do `RETURNING` não enfraqueceu a aquisição
idempotente. Batches mistos continuaram separados em accepted e rejected.

Os `31` testes de integração do adapter e os quatro testes concorrentes de
saldo passaram. A suíte completa do SPI passou com `288` testes, sem falhas.

### A/B end-to-end do update PACS.002 por outcome

O A foi o último baseline mantido,
`notification-direct-fast-path/20260819_163347`. O B autoritativo foi
`pacs002-update-by-outcome-clean/20260819_202532`, produzido após recriar a
stack e qualificar o smoke na primeira tentativa. Os snapshots de profile e
execution plan são byte a byte idênticos. O B aceitou `134.999/134.999`
originais e terminou sem outcome contraditório nem violação de replay; Kafka
também estava quiescente na checagem imediata posterior.

O mecanismo local esperado apareceu com clareza:

| transição PACS.002 | A | B | variação |
| --- | ---: | ---: | ---: |
| chamadas | `332` | `399` | `+20,18%` |
| rows | `74.767` | `67.311` | `-9,97%` |
| rows por chamada | `225,20` | `168,70` | `-25,09%` |
| tempo SQL acumulado | `20.697,584 ms` | `6.274,018 ms` | `-69,69%` |
| tempo por row | `0,276828 ms` | `0,093209 ms` | `-66,33%` |
| maior execução | `11.030,315 ms` | `185,378 ms` | `-98,32%` |

O lock/read anterior à transição também caiu de `14.171,629 ms` para
`7.834,628 ms`. Juntos, lock/read e update consumiram `34.869,213 ms` no A e
`14.108,646 ms` no B, uma redução de `59,54%` no tempo SQL acumulado dessa
parte do fluxo.

O ganho isolado, porém, não virou ganho sistêmico:

| evidência end-to-end | A | B | variação |
| --- | ---: | ---: | ---: |
| originais no ativo | `124.966` | `125.849` | `+0,71%` |
| média oferecida | `2.082,767 TPS` | `2.097,483 TPS` | `+0,71%` |
| outcomes observados | `84.826` | `75.458` | `-11,04%` |
| outcomes ausentes | `50.172` | `59.541` | `+18,68%` |
| PACS.002/s no ativo | `738,317` | `585,967` | `-20,63%` |
| payer notifications/s no ativo | `696,617` | `670,717` | `-3,72%` |
| latência p50 | `33.439,926 ms` | `35.468,961 ms` | `+6,07%` |
| latência p95 | `56.429,372 ms` | `56.558,906 ms` | `+0,23%` |
| latência p99 | `62.882,518 ms` | `60.114,454 ms` | `-4,40%` |

Ambos os runs deixaram de comprovar o piso sustentado: mínimo rolling de
`820 TPS` no A e `766 TPS` no B. O total e a média oferecida, entretanto,
foram equivalentes, o B teve pico menor (`4.666` contra `4.895`) e todas as
tentativas receberam HTTP 2xx. Assim, a regressão de conclusão não pode ser
explicada por menor admissão de pagamentos originais.

O PostgreSQL continuou saturado (`103,172% -> 102,779%` de CPU média ativa) e
outros statements ficaram mais caros por row. Também houve fragmentação do
trabalho: as chamadas da transição aumentaram e seu lote médio caiu. Esses
fatos mostram para onde o custo foi deslocado, mas não provam isoladamente a
causa da regressão. A conclusão necessária para este experimento é mais
restrita: reduzir o custo local desse update não elevou a capacidade
end-to-end do sistema.

Uma primeira execução B completa,
`pacs002-update-by-outcome/20260819_201426`, também havia produzido somente
`67.772` outcomes. Uma repetição direta sobre a mesma stack foi rejeitada pelo
load-tool ao receber uma notificação persistente do run anterior, embora o lag
Kafka estivesse zerado. Essa repetição não entrou na comparação; ela apenas
evidencia que lag Kafka zero não prova sozinho quiescência de deliveries já
persistidas no gateway. O B autoritativo acima foi executado somente depois de
novo reset completo.

**Resultado do A/B: regressão end-to-end.** A otimização reduziu drasticamente
o custo do statement, mas piorou o resultado sistêmico em uma stack limpa. A
variante permanece implementada na working tree para permitir a investigação e
a decisão explícita sobre o próximo passo; o resultado experimental, sozinho,
não autoriza desfazer automaticamente o código. O microbenchmark permanece
documentado como evidência de que esse statement isolado não é mais o gargalo
dominante a ser atacado.

### Localização post hoc da regressão observada

Os artefatos existentes permitem localizar a divergência sem outro run. O
trecho diretamente alterado ficou mais rápido também no end-to-end:

| trecho correlacionado por payment ID | A p50 | B p50 | A p95 | B p95 |
| --- | ---: | ---: | ---: | ---: |
| HTTP original até PACS.008 no recebedor | `27.173,7 ms` | `28.606,7 ms` | `49.990,0 ms` | `57.529,8 ms` |
| PACS.008 no recebedor até POST PACS.002 | `0,4 ms` | `0,6 ms` | `21,9 ms` | `26,9 ms` |
| POST PACS.002 até primeira notificação final | `8.294,5 ms` | `5.903,3 ms` | `18.796,4 ms` | `9.988,5 ms` |

Assim, o B reduziu o p95 do trecho de settlement/notificação final em `46,86%`.
A perda ocorreu antes dele: o p95 até a notificação de aceite PACS.008 piorou
`15,08%`, e somente `67.311` pagamentos chegaram a produzir PACS.002, contra
`77.615` no A. Depois da notificação PACS.008, o load-tool iniciou o PACS.002
em menos de `27 ms` no p95 de ambos os runs; essa fronteira não explica a
diferença.

A análise por coorte de início HTTP confirma onde o backlog cresceu:

| coorte relativa ao início ativo | A com PACS.008 recebido | B com PACS.008 recebido |
| --- | ---: | ---: |
| `[0 s, 10 s)` | `13.499` | `15.542` |
| `[10 s, 20 s)` | `22.477` | `21.139` |
| `[20 s, 30 s)` | `16.000` | `14.163` |
| `[30 s, 40 s)` | `12.382` | `6.585` |
| `[40 s, 50 s)` | `3.720` | `2.371` |
| `[50 s, 60 s)` | `1.513` | `192` |

O novo update não ficou represado: suas `67.311` rows alteradas são exatamente
a população de PACS.002 originais enviada no B. O lock/read viu `69.399` rows,
incluindo os `2.088` replays. No A, o update antigo havia concluído `74.767`
transições para `77.615` PACS.002 originais até o snapshot. Portanto a etapa
alterada deixou de ser limitante; o B terminou com menos PACS.002 porque menos
pagamentos chegaram até ela.

O deslocamento aparece no trabalho compartilhado do PostgreSQL. Ele permaneceu
ocupando aproximadamente uma CPU inteira nos dois runs, mas vários statements
não alterados ficaram mais caros por row no B:

| statement não alterado | A por row | B por row | variação |
| --- | ---: | ---: | ---: |
| insert do pagamento | `0,2383 ms` | `0,2531 ms` | `+6,21%` |
| insert da outbox | `0,2619 ms` | `0,3028 ms` | `+15,62%` |
| insert de auditoria | `0,1061 ms` | `0,1273 ms` | `+19,98%` |
| insert de delivery | `0,3043 ms` | `0,3585 ms` | `+17,81%` |
| persistência de ACK | `0,1752 ms` | `0,2236 ms` | `+27,63%` |
| mark published | `0,1984 ms` | `0,2272 ms` | `+14,52%` |

Não houve rollback ou deadlock. O B fez menos leituras físicas, menos trabalho
de autovacuum e menos I/O total, portanto não há evidência de que lock ou volume
de I/O produzido pelo novo update explique a regressão. Checkpoints atravessam
os três runs: o primeiro B, que foi o pior, passou quase todo o warmup e o ativo
sob um checkpoint de `117,6 s`; no B limpo o checkpoint começou `36,1 s` após o
início ativo, contra `42,3 s` no A. Isso contribui para a variabilidade, mas não
é explicação suficiente porque a curva B já se separava antes do checkpoint
limpo começar.

**Conclusão da investigação:** não houve uma inversão mágica do ganho da query.
A variante acelerou o trecho que alterou. A regressão agregada foi determinada
pela fronteira anterior de PACS.008/aceite e por um custo por row maior em todo
o ciclo compartilhado de escrita enquanto o PostgreSQL estava saturado. Os
artefatos não sustentam atribuir esse comportamento à nova query, nem permitem
isolar uma única causa ambiental entre scheduling, checkpoint e dinâmica das
filas. O estado correto da variante é: ganho local comprovado, ausência de
regressão semântica e ganho end-to-end ainda não comprovado. O próximo tuning
deve seguir o gargalo anterior observado, sem repetir este A/B apenas para
revalidar os mesmos fatos.

### Fast path pós-commit da notification outbox do SPI

A publicação inicial deixou de depender do polling da outbox. O insert em lote
agora usa `RETURNING communication_id` e devolve somente as obrigações realmente
criadas por aquela transação; replays idempotentes não entram no fast path. Um
evento interno síncrono, associado à transação, publica esse lote somente em
`AFTER_COMMIT`. Rollback não publica nada, e uma falha depois do commit não é
propagada como se a transação financeira tivesse falhado: a row `PENDING`
permanece durável para recovery.

O fast path e o recovery reutilizam o mesmo worker e o mesmo monitor, sem fila,
executor ou lifecycle adicional. Os envios Kafka do lote são iniciados antes
da espera pelas confirmações. `markPublished` e `scheduleRetry` executam em
transações `REQUIRES_NEW`, pois o callback já ocorre depois do commit original.
Rows novas recebem `next_attempt_at = clock_timestamp() + 1s`, usando o mesmo
`retry-delay` existente, e o polling de recovery mudou de `20ms` para `1s`.
Assim, o recovery não disputa imediatamente uma row que o fast path acabou de
receber.

Os testes protegem retorno somente das rows inseridas, ausência de evento para
replay, evento somente após commit, ausência de publicação no rollback,
persistência do estado pós-publicação em nova transação, serialização entre
fast path e recovery e elegibilidade tardia para recovery. A suíte completa do
SPI passou com `296` testes, sem falhas.

O ambiente foi recriado e o smoke qualificou na primeira tentativa com
`1.250/1.250` originais, `1.000/1.000` PACS.002 e `112/112` replays. O
diagnóstico único está em
`notification-outbox-post-commit-fast-path/20260819_215520`; o comparável é
`pacs002-update-by-outcome-clean/20260819_202532`. Profile e execution plan são
idênticos. A execução nova aceitou `134.999/134.999` originais, não teve outcome
contraditório nem violação de replay, terminou com Kafka quiescente e, na
checagem posterior, todas as `256.707` rows da outbox acumuladas pelo smoke e
pelo diagnóstico estavam `PUBLISHED`.

O mecanismo local esperado foi confirmado:

| trabalho da outbox | anterior | fast path | variação |
| --- | ---: | ---: | ---: |
| polls | `914` | `111` | `-87,86%` |
| rows devolvidas pelo poll | `237.223` | `0` | `-100%` |
| tempo acumulado do poll | `12.969,524 ms` | `805,566 ms` | `-93,79%` |
| rows por insert | `219,25` | `272,79` | `+24,42%` |
| tempo do insert por row | `0,3028 ms` | `0,1530 ms` | `-49,47%` |

O `pg_stat_statements` exporta somente as cinquenta queries mais caras. Como o
`markPublished` ainda gera uma forma SQL distinta para cada cardinalidade do
`IN`, a soma das formas visíveis não representa todas as chamadas e não foi
usada como total. Estruturalmente, o fast path troca o mark de lotes de recovery
de até `1.000` rows por um mark para cada lote de negócio efetivamente inserido;
portanto a redução do poll não significa eliminação de todo o custo pós-commit.

O resultado end-to-end foi misto:

| evidência | anterior | fast path | variação |
| --- | ---: | ---: | ---: |
| originais iniciados no ativo | `125.849` | `122.054` | `-3,02%` |
| mínimo rolling de 1 s | `766 TPS` | `1.148 TPS` | `+49,87%` |
| PACS.002 iniciados | `67.311` | `59.229` | `-12,01%` |
| outcomes observados | `75.458` | `74.441` | `-1,35%` |
| latência p50 | `35.468,961 ms` | `33.362,641 ms` | `-5,94%` |
| latência p95 | `56.558,906 ms` | `56.433,477 ms` | `-0,22%` |
| latência p99 | `60.114,454 ms` | `58.963,240 ms` | `-1,92%` |
| CPU média do PostgreSQL | `102,779%` | `102,284%` | `-0,48%` |

O PostgreSQL continuou saturado, e o run ainda não provou o piso sustentado nem
o SLA. A redução de polling é real e não houve regressão semântica, mas este
único diagnóstico curto não prova ganho de capacidade: houve menos PACS.002,
enquanto outcomes totais e latências ficaram próximos ou melhores. A
simplificação foi mantida pelo ganho estrutural, mas este diagnóstico isolado
não permite atribuir ganho de throughput ao fast path e não encerra a
estabilização.

### Spike de compactação terminal das notificações

Foi avaliada, sem alteração do código de produção, a hipótese de remover o
`payload` quando uma outbox vira `PUBLISHED` e uma delivery vira `ACKED`. O
objetivo do spike não era retenção: era verificar se escrever uma versão MVCC
terminal menor liberaria CPU do PostgreSQL para o restante do pipeline.

O PostgreSQL estava ocioso antes da medição. A base real continha `256.707`
outboxes `PUBLISHED` e `256.707` deliveries, das quais `196.077` estavam
`ACKED`. Outbox e delivery ocupavam, respectivamente, `336 MB` e `264 MB`. As
rows terminais preservavam payload médio de `446 B` na outbox e `381 B` na
delivery.

O benchmark criou tabelas logadas scratch com `120.000` rows, mesmos índices e
payloads reais. Comparou o update vigente com a mesma operação acrescentando
somente `payload = NULL`. Foram usados batches de `200` ACKs e `300`
publicações, com dois warmups e trinta amostras válidas por variante. A ordem A/B
foi alternada sobre os mesmos IDs. Tempo, ticks de CPU do backend e avanço de
WAL foram medidos dentro de cada operação; o relógio de CPU tinha resolução de
`10 ms`, portanto diferenças pequenas nessa coluna não são conclusivas.

| operação | variante | rows/s | tempo/row | CPU/row | WAL/row | row terminal |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| ACK delivery | atual | `58.771,67` | `0,017015 ms` | `0,016667 ms` | `844,80 B` | `603,11 B` |
| ACK delivery | compacta | `60.106,99` | `0,016637 ms` | `0,018333 ms` | `456,40 B` | `220,76 B` |
| publicação outbox | atual | `25.006,11` | `0,039990 ms` | `0,040000 ms` | `904,83 B` | `662,45 B` |
| publicação outbox | compacta | `26.123,53` | `0,038280 ms` | `0,036667 ms` | `448,10 B` | `212,63 B` |

A compactação reduziu o WAL por row em `45,98%` no ACK e `50,48%` na
publicação, além de reduzir a representação terminal em aproximadamente
`63-68%`. O efeito sobre capacidade de execução, porém, ficou muito abaixo do
limiar de `20%` definido antes da medição: throughput subiu apenas `2,27%` no
ACK e `4,47%` na publicação. A CPU amostrada não mostrou redução no ACK e caiu
somente `8,33%` na publicação, diferença pequena diante da granularidade do
contador.

**Decisão:** não compactar payloads nesta estabilização e não executar A/B
end-to-end. A hipótese oferece benefício relevante de WAL, retenção e
autovacuum para trabalho futuro, mas não demonstrou liberação suficiente do
PostgreSQL para justificar agora a perda ou reformulação da evidência técnica
de auditoria. O schema scratch foi removido e as contagens das tabelas de
produção permaneceram inalteradas.

### Spike de `fillfactor` e HOT update dos pagamentos

A tabela real mostrou uma oportunidade independente do contrato de negócio. Em
`87.479` updates de `payment_transaction_entity`, somente `18.988` foram HOT
(`21,71%`), enquanto `68.491` criaram a nova versão em outra página. A tabela
possui somente a primary key como índice e PACS.002 altera `status` e
`rejection_reason`; portanto a transição é elegível para HOT, mas o
`fillfactor=100` deixa pouco espaço na página original.

O spike usou três tabelas logadas scratch com schema, constraint e índice reais,
variando somente `fillfactor=100`, `70` e `50`. Cada variante recebeu os mesmos
`120.000` pagamentos pelo insert set-based vigente, em batches de `500` e ordem
rotativa. Em seguida, `96.000` pagamentos (`80%`) passaram pelo lock/leitura e
update de PACS.002 em batches de `200`, novamente alternando a ordem. Foram
excluídos cinco batches de warmup em cada operação. Autovacuum ficou desligado
somente nas tabelas scratch para não alterar a comparação durante a medição.
Os ticks de CPU do próprio backend foram agregados sobre todos os batches, com
resolução de `10 ms`.

| operação | fillfactor | rows/s | tempo/row | CPU/row | WAL/row |
| --- | ---: | ---: | ---: | ---: | ---: |
| insert | `100` | `42.067,52` | `0,023771 ms` | `0,022809 ms` | `358,59 B` |
| insert | `70` | `41.574,25` | `0,024053 ms` | `0,024170 ms` | `358,59 B` |
| insert | `50` | `40.750,94` | `0,024539 ms` | `0,025106 ms` | `358,59 B` |
| transição | `100` | `59.115,15` | `0,016916 ms` | `0,017789 ms` | `447,68 B` |
| transição | `70` | `63.646,43` | `0,015712 ms` | `0,016000 ms` | `340,18 B` |
| transição | `50` | `88.516,60` | `0,011297 ms` | `0,012737 ms` | `177,07 B` |

O resultado físico explica a diferença:

| fillfactor | HOT updates | nova página | HOT ratio | heap final | índice final |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `100` | `5.147` | `90.853` | `5,36%` | `38 MB` | `10 MB` |
| `70` | `45.105` | `50.895` | `46,98%` | `45 MB` | `10 MB` |
| `50` | `96.000` | `0` | `100%` | `45 MB` | `5.848 kB` |

No recorte executado, com um insert para todo pagamento e a transição
`ACCEPTED` para `80%`, o ciclo medido mudou assim contra `fillfactor=100`:

| variante | pagamentos/s | tempo/pagamento | CPU/pagamento | WAL/pagamento | variação de throughput |
| --- | ---: | ---: | ---: | ---: | ---: |
| `100` | `26.703,56` | `0,037448 ms` | `0,037191 ms` | `720,54 B` | baseline |
| `70` | `27.206,06` | `0,036757 ms` | `0,037106 ms` | `633,63 B` | `+1,88%` |
| `50` | `29.697,11` | `0,033673 ms` | `0,035404 ms` | `501,75 B` | `+11,21%` |

`fillfactor=50` aumentou em `49,74%` o throughput da transição. O insert ficou
`3,13%` mais lento, mas o ciclo combinado ainda reduziu tempo em `10,08%`, CPU
em `4,81%` e WAL em `30,36%`. O custo de espaço líquido foi pequeno: depois das
transições, heap mais índice ocuparam aproximadamente `51 MB`, contra `48 MB`
no baseline, pois os updates não-HOT também ampliam o índice e a heap.

Esse recorte subestima a frequência de update do workload completo. Os `20%`
de insufficient-funds também são inseridos em `WAITING_ACCEPTANCE` e
atualizados, na mesma transação de ingresso, para `REJECTED`. Assim, quando o
pipeline termina, cada pagamento original tende a produzir um insert e uma
transição de status: happy-path no PACS.002 e insufficient-funds ainda no
PACS.008. O spike isolou a forma `ACCEPTED`; não mediu separadamente o SQL de
rejeição e, por isso, os `11,21%` não devem ser tratados como estimativa exata
do workload misto. A omissão não enfraquece a seleção de `fillfactor=50` para o
A/B end-to-end, onde as duas formas serão exercitadas.

**Resultado do spike:** `fillfactor=70` não oferece ganho suficiente;
`fillfactor=50` é candidato positivo para um A/B end-to-end isolado. A
estatística real já possui HOT ratio maior que o baseline scratch por efeito de
layout e vacuum, então o ganho sistêmico não deve ser extrapolado diretamente
dos `11,21%`. Nenhuma tabela operacional ou código de produção foi alterado; o
schema scratch foi removido.

### A/B end-to-end de `fillfactor=50`

O candidato foi implementado experimentalmente em uma migração que define
`fillfactor=50` em `payment_transaction_entity`, protegido por um teste de
integração que lê `pg_class.reloptions`. A suíte completa passou com `202`
testes do SPI, além dos testes Go, dos `17` scripts shell, da sintaxe Bash e da
validação do Compose.

O ambiente foi recriado com volumes limpos. A migração foi aplicada antes da
criação das rows e o smoke qualificou na primeira tentativa com `1.250/1.250`
originais, `1.000/1.000` PACS.002 e `112/112` replays. Antes da medição foram
confirmados `{fillfactor=50}` e contadores zerados para a tabela. A única
execução B1 está em `payment-fillfactor-50/20260819_233034`; o baseline A é
`notification-outbox-post-commit-fast-path/20260819_215520`. Os snapshots de
profile e execution plan são byte a byte idênticos entre A e B1.

A propriedade física esperada foi confirmada:

| evidência PostgreSQL | A, `fillfactor=100` | B1, `fillfactor=50` | variação B1/A |
| --- | ---: | ---: | ---: |
| HOT updates da tabela | `18.988/87.479` (`21,71%`) | `84.282/84.282` (`100%`) | mecanismo confirmado |
| insert, tempo/row | `0,154779 ms` | `0,225299 ms` | `+45,56%` |
| lock/leitura PACS.002, tempo/row | `0,263696 ms` | `0,084041 ms` | `-68,13%` |
| update `ACCEPTED`, tempo/row | `0,380564 ms` | `0,040710 ms` | `-89,30%` |
| update `REJECTED`, tempo/row | `0,542669 ms` | `0,026350 ms` | `-95,14%` |
| update `ACCEPTED`, WAL/row | `302,41 B` | `117,09 B` | `-61,28%` |
| update `REJECTED`, WAL/row | `381,06 B` | `198,23 B` | `-47,98%` |

O custo acumulado das cinco formas SQL associadas ao pagamento caiu `69,48%`,
embora com quantidades de rows próximas, enquanto o insert isolado ficou mais
caro. Ao final, a tabela ocupava aproximadamente `50,7 MB` de heap e `8,4 MB`
de índice. Portanto o efeito local não é uma extrapolação do spike: o A/B
confirmou HOT updates e redução forte do trabalho de transição no workload
real.

O resultado end-to-end, porém, foi misto:

| evidência | A | B1 | variação B1/A |
| --- | ---: | ---: | ---: |
| originais iniciados no ativo | `122.054` | `128.267` | `+5,09%` |
| mínimo rolling de 1 s | `1.148 TPS` | `549 TPS` | `-52,18%` |
| PACS.002 iniciados | `59.229` | `57.307` | `-3,25%` |
| outcomes observados | `74.441` | `70.980` | `-4,65%` |
| outcomes ausentes ao deadline | `60.558` | `64.020` | `+5,72%` |
| latência p50 | `33.362,641 ms` | `30.710,621 ms` | `-7,95%` |
| latência p95 | `56.433,477 ms` | `60.624,810 ms` | `+7,43%` |
| latência p99 | `58.963,240 ms` | `61.254,092 ms` | `+3,89%` |
| CPU média do PostgreSQL | `102,284%` | `103,478%` | `+1,17%` relativo |

A B1 aceitou `135.000/135.000` originais por HTTP, não produziu outcome
contraditório nem violação de replay e preservou todos os artefatos. Ainda
assim, como A e B1 permaneceram inválidos para o piso sustentado e o SLA, essa
execução isolada não autorizou manter nem descartar o candidato.

Após a B1, o mecanismo local e um indicador de capacidade haviam melhorado,
mas métricas principais também regrediram mais de `5%`. Esses resultados mistos
motivaram a autorização de uma única repetição limpa.

Na preparação da repetição, a primeira stack parou antes de gerar qualquer
pagamento porque um prewarm HTTP/2 expirou no `/health`; ela não entrou na
comparação. Uma nova stack limpa qualificou o smoke na primeira tentativa com
as mesmas contagens da preparação anterior e Kafka quiescente. A B2 está em
`payment-fillfactor-50-repeat/20260819_234548`; profile e execution plan são
idênticos aos de A e B1. Ela aceitou `135.000/135.000` originais por HTTP,
`8.716/8.716` replays, sem outcome contraditório nem violação de replay.

O mecanismo local se repetiu: os `85.049` updates foram `100%` HOT. Contra A,
o tempo por row do lock/leitura caiu `65,95%`, o update `ACCEPTED` caiu
`89,49%` e o `REJECTED` caiu `93,87%`; o insert ficou `46,99%` mais caro. O WAL
por row das duas transições caiu, respectivamente, `61,28%` e `47,95%`.

| evidência end-to-end | A | B1 | B2 | variação B2/A |
| --- | ---: | ---: | ---: | ---: |
| originais iniciados no ativo | `122.054` | `128.267` | `127.179` | `+4,20%` |
| mínimo rolling de 1 s | `1.148 TPS` | `549 TPS` | `590 TPS` | `-48,61%` |
| PACS.002 iniciados | `59.229` | `57.307` | `59.097` | `-0,22%` |
| outcomes observados | `74.441` | `70.980` | `73.374` | `-1,43%` |
| outcomes ausentes ao deadline | `60.558` | `64.020` | `61.626` | `+1,76%` |
| latência p50 | `33.362,641 ms` | `30.710,621 ms` | `31.556,975 ms` | `-5,41%` |
| latência p95 | `56.433,477 ms` | `60.624,810 ms` | `62.580,814 ms` | `+10,89%` |
| latência p99 | `58.963,240 ms` | `61.254,092 ms` | `63.341,054 ms` | `+7,42%` |
| CPU média do PostgreSQL | `102,284%` | `103,478%` | `102,325%` | `+0,04%` relativo |

As duas execuções B confirmaram o mecanismo físico, mas a comparação
end-to-end não permite decidir sobre o candidato. O baseline iniciou `122.054`
originais no ativo, enquanto B1 iniciou `128.267` e B2, `127.179`. A diferença
veio de trabalho atrasado no warmup: A carregou `2.055` posições para o ativo,
B1 carregou `8.267` e B2, `7.179`. O mínimo menor e o máximo maior são as duas
faces desse mesmo fenômeno: workers bloqueados reduzem os inícios e, quando são
liberados, o scheduler recupera a dívida em rajadas.

Assim, o A/B atual continua provando que `fillfactor=50` produz `100%` de HOT
updates, reduz drasticamente o custo e o WAL das transições e encarece o insert
em aproximadamente `47%`. Ele não separa, porém, o efeito sistêmico desse
trade-off do efeito de uma workload ativa mais concentrada e maior nos runs B.
A migração e o teste experimentais permanecem na branch até decisão explícita;
os bundles de A, B1 e B2 permanecem preservados.

### No-carry-over implementado — novo A/B de `fillfactor` concluído

O gerador deixou de recuperar posições temporais perdidas. Um pagamento
planejado que ainda não iniciou não é um pagamento real do cliente; se sua
oportunidade temporal expirar, ele deixa de existir sem criar payload, seleção
de replay, evento CSV ou POST HTTP. A ausência continua aparecendo no
pós-processamento como piso rolling abaixo de `target_tps`, portanto um pico
posterior nunca compensa a falha.

O contrato da mudança é:

1. dívida do warmup nunca atravessa `activeStart`;
2. dívida criada dentro do ativo não é recuperada em janelas posteriores;
3. cada posição aceita no máximo `10 ms` de atraso; ao perceber posições mais
   antigas, o scheduler calcula diretamente a primeira posição ainda válida,
   sem iterar uma a uma nem executar catch-up além dessa tolerância;
4. o descarte ocorre antes de qualquer efeito observável associado ao
   pagamento;
5. picos naturais acima de `target_tps` continuam permitidos, mas não podem ser
   produzidos para pagar posições perdidas;
6. `RequestStartedAtNS` permanece a fonte do report, e qualquer janela rolling
   contínua de um segundo abaixo de `target_tps` invalida a comprovação do piso;
7. em uma execução válida não há posições perdidas, portanto o no-carry-over
   não reduz a workload que aprova o contrato.

A implementação mantém warmup e ativo como fases semiabertas independentes,
usa um canal sem buffer entre scheduler e workers e somente materializa ID,
cenário, valor e seleção de replay dentro do worker, depois de uma segunda
checagem do deadline. Os profiles, os artefatos e o contrato do
`sla-report.json` não mudaram. Testes unitários cobrem a cadência sem drift,
o salto sobre um atraso de `49 s`, a fronteira de fase e a ausência de efeitos
para slots expirados; a suíte Go completa passou após a mudança.

O diagnóstico curto
`load-test/results/no-carry-over-diagnostic/20260820_004213` confirmou o
envelope temporal: foram iniciados `14.933 / 15.000` originais no warmup e
`119.754 / 120.000` no ativo, nenhum fora da janela, com mínimo/máximo rolling
de `1.932 / 2.019 TPS`. Portanto as posições perdidas não atravessaram a
fronteira nem produziram a rajada de compensação observada anteriormente. A
execução não gerou `sla-report.json` porque, ao abrir as streams, recebeu uma
notificação residual do run de `23:46` (`go-1787193977...`), diferente do
prefixo atual (`go-1787197357...`); o simulador recusou corretamente criar um
PACS.002 sem metadata da execução corrente. Essa contaminação impede usar o
run como qualificação funcional, mas não altera a evidência temporal obtida
dos PACS.008 do prefixo atual.

Com essa correção, o novo A/B foi executado em stacks limpas e com o mesmo
profile, execution plan, código e procedimento:

1. A, `payment-fillfactor-100-no-carry/20260820_005539`, usou
   `payment_transaction_entity` em `fillfactor=100`;
2. B, `payment-fillfactor-50-no-carry/20260820_010212`, usou a migração da
   branch com `fillfactor=50`;
3. cada variante foi recriada com volumes limpos, qualificou o smoke na
   primeira tentativa com `1.250/1.250` originais, teve `reloptions`
   confirmado e iniciou a medição com os contadores da tabela zerados;
4. os snapshots do profile possuem o mesmo SHA-256
   `64b14f9379a0044e90a202dcd815107301cfd16d340667cd16a3adc369f2b700`;
5. os execution plans possuem o mesmo SHA-256
   `5648781b7b99d582ac1ab9fba89d3c78421f31533d70d5bb36cc7be2c631500d`;
6. antes de A, o diff de código continha somente a troca temporária de
   `fillfactor` e sua expectativa de teste; antes de B, a working tree já havia
   retornado exatamente ao commit da branch.

O no-carry-over removeu a distorção que invalidou a comparação anterior. A e B
não iniciaram pagamentos fora da janela, e o máximo rolling permaneceu junto
ao envelope de `2.000 TPS`, em vez dos picos de `3.672–5.749 TPS` causados por
catch-up:

| workload observado | A, `fillfactor=100` | B, `fillfactor=50` | variação B/A |
| --- | ---: | ---: | ---: |
| originais iniciados no run | `116.777` | `120.830` | `+3,47%` |
| originais iniciados no ativo | `109.713` | `112.429` | `+2,48%` |
| TPS médio no ativo | `1.828,550` | `1.873,817` | `+2,48%` |
| mínimo rolling de 1 s | `529 TPS` | `868 TPS` | `+64,08%` |
| máximo rolling de 1 s | `2.020 TPS` | `2.019 TPS` | `-0,05%` |
| PACS.002 iniciados | `55.231` | `56.780` | `+2,80%` |

Ambas as variantes aceitaram por HTTP todos os originais efetivamente
iniciados, não produziram outcome contraditório nem violação de replay. Nenhuma
comprovou ainda o piso sustentado ou o SLA. B iniciou mais trabalho, mas a
quantidade de outcomes concluídos até o deadline permaneceu praticamente
constante:

| resultado end-to-end | A, `fillfactor=100` | B, `fillfactor=50` | variação B/A |
| --- | ---: | ---: | ---: |
| outcomes correspondentes | `70.025` | `70.014` | `-0,02%` |
| outcomes ausentes | `46.752` | `50.816` | `+8,69%` |
| latência p50 | `28.141,829 ms` | `30.006,050 ms` | `+6,62%` |
| latência p95 | `52.056,242 ms` | `50.716,451 ms` | `-2,57%` |
| latência p99 | `55.387,357 ms` | `52.198,466 ms` | `-5,76%` |
| CPU média do PostgreSQL no ativo | `103,538%` | `102,600%` | `-0,91%` relativo |

O mecanismo local voltou a aparecer sem depender da workload temporal. A
terminou com `17.966/78.588` updates HOT (`22,86%`); B, com
`79.539/79.539` (`100%`). Ao final da medição, heap mais índices ocupavam
`32,27 MiB` em A e `47,43 MiB` em B (`+46,98%`), o custo de espaço esperado ao
reservar metade de cada página. O custo por row registrado por
`pg_stat_statements` foi:

| forma SQL | A, tempo/row | B, tempo/row | variação | A, WAL/row | B, WAL/row | variação |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| insert do pagamento | `0,212872 ms` | `0,243023 ms` | `+14,16%` | `400,30 B` | `386,11 B` | `-3,54%` |
| lock/leitura PACS.002 | `0,267311 ms` | `0,289313 ms` | `+8,23%` | `73,51 B` | `82,57 B` | `+12,32%` |
| update `ACCEPTED` | `0,074484 ms` | `0,047132 ms` | `-36,72%` | `295,70 B` | `117,18 B` | `-60,37%` |
| update `REJECTED` | `0,100552 ms` | `0,027084 ms` | `-73,06%` | `371,01 B` | `198,30 B` | `-46,55%` |

Não há atribuição de CPU por statement neste A/B: habilitar novamente
`log_executor_stats` mudaria a instrumentação entre a comparação e os runs
anteriores. A evidência disponível separa tempo de parede e WAL por row e usa a
CPU do container PostgreSQL para o efeito agregado.

O resultado separa os efeitos do candidato. `fillfactor=50` reduz de forma
forte o custo e o WAL das transições, ao preço de um insert e de uma
lock/leitura mais caros. No sistema completo, ele permitiu iniciar `2,48%` mais
originais no ativo e melhorou o piso observado sem criar pico de compensação,
mas não aumentou a quantidade de outcomes concluídos dentro do experimento. O
PostgreSQL permaneceu saturado nas duas variantes, e o trabalho dominante
continuou no ciclo de outbox/delivery/ACK, não nos updates de status que o
fillfactor otimiza.

Portanto o A/B agora é comparável e expõe um trade-off real, mas não transforma
`fillfactor=50` na solução do throughput end-to-end. A decisão de manter a
migração, voltar ao layout padrão ou medir `fillfactor=70` permanece explícita
para a revisão do usuário; nenhuma dessas ações deve ser automatizada a partir
dos resultados.

### Gargalo após o A/B de `fillfactor`: ciclo persistente de notificações

A investigação usou o B
`payment-fillfactor-50-no-carry/20260820_010212`, sem nova execução. A CPU média
do PostgreSQL no ativo foi `102,600%`. Das `366` amostras com backend de
aplicação ativo, `271` (`74,04%`) estavam executáveis, sem wait event; esperas
por lock foram residuais. As esperas de I/O também não explicam o limite: mesmo
nas quatro operações persistentes de notificação, a fração do tempo SQL
atribuída a leitura ou escrita de blocos ficou entre aproximadamente `7,8%` e
`19,6%`. O servidor está predominantemente consumindo o único core disponível,
e não esperando um lock ou o storage.

No caminho saudável vigente, cada obrigação lógica atravessa quatro mutações
duráveis no mesmo PostgreSQL:

1. o SPI insere a outbox na mesma transação de pagamento e auditoria;
2. depois do commit e do ACK do Kafka, o SPI muda a outbox de `PENDING` para
   `PUBLISHED` em uma transação nova;
3. o gateway consome o Kafka e insere a delivery como `IN_FLIGHT` antes do
   primeiro envio gRPC;
4. o ACK do PSP muda a delivery para `ACKED` em batch.

O fast path já retirou o claim do caminho normal. No B, as `102` chamadas do
recovery não devolveram nenhuma row; portanto o claim não voltou a ser o
gargalo. As quatro mutações acima preservam duas fronteiras reais de falha:
outbox antes do Kafka e delivery antes do gRPC. Remover uma delas não é uma
otimização local: exige redefinir o contrato de recuperação.

O custo visível no snapshot de `pg_stat_statements` foi:

| operação | chamadas visíveis | rows | rows/chamada | tempo/row | WAL/row |
| --- | ---: | ---: | ---: | ---: | ---: |
| insert da outbox | `719` | `189.187` | `263,14` | `0,238513 ms` | `1.070,8 B` |
| publicação da outbox | `179` | `74.150` | `414,25` | `0,200157 ms` | `841,1 B` |
| insert da delivery | `1.276` | `182.466` | `143,00` | `0,295106 ms` | `1.150,3 B` |
| ACK da delivery | `1.007` | `182.208` | `180,94` | `0,203580 ms` | `848,6 B` |

A publicação da outbox está subcontada: seu `IN (?, ...)` produz uma query
distinta para cada cardinalidade, e somente `38` dessas formas couberam entre
as cinquenta queries exportadas. Os `74.150` rows visíveis não representam as
`189.187` obrigações inseridas. Esse formato também força construção, parsing e
planejamento de SQL proporcionais ao lote. É complexidade acidental; a mesma
operação pode usar um statement fixo com array sem mudar estado, transação ou
idempotência.

Mesmo usando somente o custo unitário visível, uma notificação concluída gera
aproximadamente `3,9 KB` de WAL nas quatro etapas. As tabelas observadas depois
do run tinham payload médio de cerca de `438 B` armazenado duas vezes, uma row
de aproximadamente `656-659 B` em cada fronteira e zero HOT updates. A mudança
para `PUBLISHED` remove a row do índice parcial da outbox; a mudança para
`ACKED` a remove dos dois índices parciais da delivery. Portanto ambas precisam
manter índices e criar novas versões de heap mesmo sem alterar o payload.

O dimensionamento mostra por que o custo distribuído é dominante. No workload
80/20, um happy path cria uma solicitação de aceite e duas notificações finais;
insufficient funds cria uma rejeição. A média é, portanto, `2,6` notificações
lógicas por pagamento. Provar `2.000` pagamentos/s exige sustentar cerca de
`5.200` notificações/s, equivalentes a aproximadamente `20.800` mutações de row
por segundo apenas neste protocolo persistente, além de pagamento, saldo e
auditoria.

As próximas hipóteses ficam separadas por alcance e ROI:

1. **Remover primeiro a complexidade acidental de `markPublished`:** trocar o
   `IN` dinâmico por um statement fixo com array. É a menor mudança e torna a
   telemetria completa em um único query ID, mas não reduz as quatro mutações
   nem seu WAL por row; o ganho esperado é limitado a parser, planner e
   construção do SQL.
2. **Medir batching antes de ajustar configuração:** delivery e ACK ficaram em
   `143` e `181` rows por chamada, abaixo dos limites de `500`. Um
   microbenchmark deve medir o custo por row nas cardinalidades observada e
   máxima antes de aumentar `fetch.min.bytes`, espera do Kafka ou intervalo do
   ACK. Essa opção preserva o modelo, mas troca latência por menos transações e
   não elimina a amplificação por row.
3. **Só então avaliar mudança estrutural:** reduzir estado terminal, payload
   duplicado ou manutenção de índices pode atacar WAL e heap, porém precisa
   preservar outbox atômica, delivery durável, deduplicação e redelivery até
   ACK. O spike anterior de `payload = NULL` reduziu WAL em cerca de `46-50%`,
   mas aumentou throughput somente `2-4%`; sozinho, ele não é o próximo alvo de
   maior ROI.

O sampler de `pg_stat_activity` acumulou aproximadamente `12,678 s`, ou `5,57%`
do tempo das cinquenta queries exportadas. Ele foi igual nas variantes do A/B
e não cria o gargalo funcional, mas deve ser desligado numa futura execução de
qualificação depois que a atribuição deixar de ser necessária.

### Intervenção vigente: delivery pull com cursor do PSP

O candidato estrutural implementado substitui integralmente a metade
Gateway → PSP do reliable push por pull unary sobre a conexão gRPC HTTP/2/mTLS
já existente. A outbox confiável do SPI permanece. A persistência larga de
`notification_delivery` foi posteriormente substituída pelo `delivery_index`
mínimo da Fase 1 híbrida. Saem do Gateway o ACK individual, `ACKED`,
`IN_FLIGHT`, lease, claim, worker de recovery, retry ativo e todos os
índices/configurações exclusivos desse lifecycle push.

Cada PSP mantém um fluxo lógico e apresenta o último cursor que processou
duravelmente. `PullNotifications` recebe somente esse cursor. O Gateway
autentica o PSP pelo certificado, valida o cursor opaco HMAC vinculado ao ISPB
e devolve imediatamente até `15` rows posteriores disponíveis mais o próximo
cursor. Se não houver backlog, o long poll vigente espera trabalho novo ou seu
timeout. O PSP só avança depois de processar o lote inteiro; cursor antigo
causa redelivery do lote e preserva at-least-once. Um segundo pull simultâneo
do mesmo PSP é rejeitado.

O cursor usa `delivery_position` própria do Gateway, não partition/offset do
Kafka. A posição agora é consecutiva dentro do fluxo de cada PSP. Um advisory
lock transacional por ISPB serializa somente alocações concorrentes do mesmo
destinatário; PSPs diferentes permanecem independentes. A migração exige
`notification_delivery` vazia; não há compatibilidade com backlog anterior.

O profile não contém configuração de tamanho de pull nem versionamento próprio.
O limite `15` pertence ao protocolo do Gateway. O `sla-report.json` registra,
somente na janela ativa, a distribuição real de lotes não vazios (`count`,
`mean`, `p50`, `p95`, `max`), mantendo
`empty_responses` separado. Observar lote acima do máximo configurado invalida
o run; o tamanho dos batches não é SLA de negócio.

O diagnóstico executou uma medição curta e limpa para cada valor
`1/10/15/20/500`,
mantendo workload e recursos, e comparou com o baseline push
`payment-fillfactor-50-no-carry/20260820_010212`, registrando os batches reais,
CPU/SQL/WAL do PostgreSQL, outcomes e cauda. Nenhum resultado deve remover ou
reverter automaticamente a implementação: a decisão final permanece com a
revisão do usuário.

O primeiro diagnóstico revelou uma diferença importante entre o mock e um PSP
real: o simulador não persiste cursor entre processos, enquanto a preparação
deixa o smoke no backlog durável. O loadtool agora inicia no cursor vazio,
avança normalmente por essas rows e ignora somente notificações cujos
`EndToEndId` não pertencem à execução corrente. Não há truncate nem consulta
direta ao banco. O smoke de regressão
`pull-backlog-regression-smoke/20260820_213710` processou o backlog anterior e
ainda concluiu o run corrente com `1.250/1.250` pagamentos, `1.000/1.000`
PACS.002 e `3.250` notificações correlacionadas, sem erro de cursor ou pull
concorrente.

### Diagnóstico curto do tamanho de lote do pull

Cada variante foi executada após recriar os volumes e qualificar o smoke. Os
bundles medidos são:

- `pull-batch-1-clean/20260820_214150`;
- `pull-batch-10-clean/20260820_214831`;
- `pull-batch-15-clean/20260820_233923`;
- `pull-batch-20-clean/20260820_233046`;
- `pull-batch-500-clean/20260820_215410`.

| sinal | push anterior | pull 1 | pull 10 | pull 15 | pull 20 | pull 500 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| mínimo rolling TPS | `868` | `1.027` | `1.903` | `1.944` | `1.158` | `229` |
| pagamentos ativos iniciados | `112.429` | `116.364` | `119.752` | `119.851` | `116.442` | `106.951` |
| outcomes finais observados | `70.014` | `42.966` | `92.721` | `100.404` | `101.618` | `89.453` |
| batch real médio | — | `1,000` | `8,523` | `11,506` | `13,265` | `22,571` |
| batch real p95 / máximo | — | `1 / 1` | `10 / 10` | `15 / 15` | `20 / 20` | `115 / 446` |
| CPU média Gateway no ativo | `40,34%` | `60,01%` | `31,82%` | `23,66%` | `33,72%` | `36,25%` |
| CPU média PostgreSQL no ativo | `102,60%` | `103,02%` | `101,66%` | `100,19%` | `102,92%` | `101,65%` |
| tempo SQL das 50 queries exportadas | `227,588 s` | `229,460 s` | `192,302 s` | `184,818 s` | `170,831 s` | `180,863 s` |

O batch `1` confirmou o pior caso de overhead: o SELECT de pull fez `119.300`
chamadas, consumiu `43,245 s` e devolveu `115.551` rows. Com batch `10`, foram
`40.913` chamadas, `17,018 s` e `247.488` rows. Com batch `15`, foram `37.218`
chamadas, `12,334 s` e `268.055` rows; com `20`, `33.178` chamadas, `11,714 s`
e `264.473` rows. Com batch `500`, foram `18.865` chamadas, `9,257 s` e
`234.149` rows. O batch `15` foi o melhor equilíbrio observado entre batching
real e preservação da workload. `20` reduziu um pouco mais o read path e
concluiu mais outcomes, mas degradou a geração para `1.158` TPS mínimos e
elevou a CPU do Kafka Producer para `72,79%`. O batch `500` acentuou a perda de
slots com grandes lotes processados em rajada pelo mesmo loadtool; nenhum dos
dois prova capacidade superior apesar da menor cauda observada.

No baseline push, a persistência de ACK consumiu `37,094 s`, escreveu
`154,623 MB` de WAL e alterou `182.208` rows; o recovery sem trabalho consumiu
mais `3,470 s`. Ambos desapareceram. No pull `15`, o read path substituto
consumiu `12,334 s` e zero WAL. O WAL total exportado não caiu porque essa
variante concluiu e persistiu muito mais trabalho (`271.900` deliveries contra
`182.466` no push), portanto a comparação correta precisa considerar trabalho
útil, e não apenas bytes absolutos.

Nenhuma variante provou `2.000 TPS` sustentados nem os SLAs finais. Pull `15`
chegou mais perto do envelope, com `1.944` TPS mínimos e `100.404` outcomes,
mas ainda teve `34.304` outcomes ausentes no deadline. Não houve outcome
contraditório, replay inválido, PACS.002 sem HTTP 2xx ou batch acima do limite.

Com base nesses resultados, `15` foi escolhido como limite fixo do protocolo,
e não como default configurável. Ele apresentou o melhor equilíbrio observado:
preservou melhor a workload (`1.944` TPS mínimos), produziu `100.404` outcomes,
reduziu o p99 de `60,045 s` para `50,993 s` frente ao batch `10` e diminuiu o
trabalho SQL do pull, sem a perda de slots observada com `20` e `500`. O
perfil de diagnóstico `500` e o de `1` foram removidos; seus bundles permanecem
somente como evidência histórica que fundamenta esta decisão.
`PullNotifications` não aceita tamanho de lote, profiles não o configuram e o
Gateway sempre limita a resposta a `15` notificações.

### Fase 1 híbrida — projeção mínima e buffer recente

A Fase 1 preservou o publisher confiável da `notification_outbox`, seus estados
`PENDING/PUBLISHED`, retry e publicação Kafka com `acks=all`. Kafka ainda faz
parte da correctness neste checkpoint. No Gateway, a cópia larga do payload foi
substituída por:

```text
delivery_index
  communication_id      PK
  recipient_ispb
  delivery_position     posição local ao PSP

UNIQUE (recipient_ispb, delivery_position)
```

O consumer decodifica o poll completo e chama uma única operação de indexação.
Ela deduplica `communication_id`, rejeita associação divergente de destinatário,
adquire locks por PSP em ordem determinística, atribui posições somente às
notificações novas e faz um bulk insert estreito. Replays não consomem posição.
Depois do commit, as novas posições e seus payloads Kafka entram numa janela
efêmera ordenada de até `150` itens por PSP, e long polls afetados são
sinalizados.

O Pull usa RAM somente quando ela contém uma sequência contígua começando em
`cursor + 1`. Em gap, restart ou cursor anterior à janela, responde diretamente
com `delivery_index JOIN notification_outbox ORDER BY delivery_position LIMIT
15`; não existe lifecycle separado de reidratação. A migração recusa substituir
uma `notification_delivery` não vazia e preserva seus dados em caso de erro.

O primeiro diagnóstico expôs uma implementação acidentalmente cara da leitura
da última posição. `MAX(delivery_position) GROUP BY recipient_ispb` escolheu
`Parallel Seq Scan`; para 20 PSPs, o `EXPLAIN ANALYZE` mediu cerca de `41 ms`.
A forma final usa um lookup lateral por PSP, com `Index Only Scan Backward` em
`delivery_index_recipient_position_key`, e mediu aproximadamente `1,3 ms` no
mesmo estado. No run anterior à correção, as variantes de `MAX` acumularam
`25,872 s`; no bundle final, o lookup indexado acumulou `1,038 s`.

O smoke qualificado foi
`environment-setup-20260821_015557-2578183-attempt-1/20260821_015733`: iniciou
os `1.250` pagamentos planejados, observou todos os outcomes esperados, não
registrou contradições nem violações de replay e deixou Kafka quiescente.

O checkpoint B final é
`delivery-index-phase1-indexed-tail/20260821_015925`, comparado ao baseline pull
15 `pull-batch-15-clean/20260820_233923`:

| sinal | baseline largo | Fase 1 final |
| --- | ---: | ---: |
| pagamentos ativos iniciados | `119.851` | `117.168` |
| TPS ativo médio / mínimo rolling | `1.997,517 / 1.944` | `1.952,800 / 983` |
| outcomes finais observados | `100.404` | `94.198` |
| latência p50 / p95 / p99 | `25,729 / 49,251 / 50,993 s` | `33,331 / 47,106 / 55,494 s` |
| CPU média PostgreSQL no ativo | `100,19%` | `102,99%` |
| CPU média Gateway no ativo | `23,66%` | `40,22%` |
| insert largo / insert do índice | `51,259 s` | `13,728 s` |
| WAL do insert de delivery | `289,593 MB` | `130,251 MB` |
| SELECT do Pull / fallback join | `12,334 s` | `4,855 s` |
| lookup de ID + última posição | — | `2,774 + 1,038 s` |

Somando o trabalho SQL diretamente pertencente à persistência e leitura do
Gateway, o recorte caiu de aproximadamente `63,593 s` para `22,396 s`
(`-64,8%`). O WAL do insert caiu cerca de `55,0%`, apesar de a Fase 1 processar
`254.131` índices contra `271.900` deliveries do baseline. Não apareceu
`INSERT INTO notification_delivery`; o caminho novo foi efetivamente
exercitado.

Isso comprova a remoção do custo específico pretendido, mas não comprova a
capacidade de `2.000 TPS`: o PostgreSQL permaneceu saturado, a
`notification_outbox` continuou dominante e o run teve `33.252` outcomes
ausentes no deadline. Não houve outcome contraditório, replay inválido,
PACS.002 sem HTTP 2xx ou lote de Pull acima de `15`. A piora do mínimo rolling,
do p50/p99 e da CPU de aplicação impede inferir ganho end-to-end a partir desta
única amostra. A implementação não é revertida nem promovida automaticamente;
a decisão arquitetural permanece com a revisão do usuário.

### Fronteira determinística entre warmup e ativo

A comparação da Fase 1 revelou que o preparador e o início do run misturavam
três responsabilidades diferentes: readiness, aquecimento e inferência de
quiescência. O contrato vigente passa a ser menor e observável pelo próprio
load-tool:

```text
reset/build/readiness
        ↓
warmup na taxa/duração declaradas pelo profile
        ↓
esperar trabalho observável do warmup (máximo 120 s)
        ↓
active 2.000 TPS
```

O preparador somente recria a stack e espera readiness. Foram removidos o smoke
automático, retries e qualificador do smoke, sleeps fixos e verificações de lag
Kafka usadas como heurística de quiescência. O load-tool não promete que não
existe trabalho interno residual: ele garante somente que não abre o ativo
enquanto ainda existe uma obrigação de warmup que ele próprio criou e consegue
observar.

O gate usa um contador de fase pequeno, integrado ao lifecycle já existente.
PACS.008 original, outcome final esperado, replay PACS.008 selecionado,
PACS.002 original e replay PACS.002 selecionado pertencem à fase. Continuações
são registradas antes de concluir a ação que as originou; assim o contador não
pode chegar transitoriamente a zero antes de um replay futuro já selecionado.
Duplicatas at-least-once não criam uma nova obrigação lógica e um outcome
contraditório falha o gate.

`run-window.json` registra `warmup_ended_at` e o `active_started_at` real. A
validade de performance continua restrita à janela ativa e a correção continua
avaliada no run inteiro. Os profiles declaram explicitamente taxa, duração e
timeout de conclusão do warmup; não existe mais a regra implícita `target / 2`.
Os profiles `mixed-outcomes-2k-15m` e `mixed-outcomes-2k-diagnostic` usam
`1.500 TPS / 120 s / 120 s`, configuração validada pelo critério de aquecimento
com JFR.

A validação do aquecimento foi concluída com o warmup de `1.500 TPS / 120 s` e
gate de até `120 s`; a Fase 2 da migração híbrida pode prosseguir. Para cada JVM,
o critério usado foi limitar a compilação na janela ativa à referência
estabilizada mais `max(20% da referência, 1 s por minuto ativo)`. Essa validação
não qualifica capacidade, throughput nem SLA end-to-end.

#### Validação do primeiro parâmetro de warmup

A stack foi recriada uma vez e duas execuções idênticas foram realizadas sem
reset entre elas:

- primeira JVM: `warmup-gate-first/20260821_035238`;
- referência estabilizada: `warmup-gate-stabilized/20260821_035613`.

O gate concluiu corretamente nas duas execuções. Na primeira, a geração de
warmup terminou às `03:54:00` e o ativo começou às `03:54:10`; na segunda, os
instantes foram `03:57:28` e `03:57:55`. Portanto nenhum ativo começou enquanto
o load-tool ainda possuía obrigações observáveis da própria fase.

As duas execuções aceitaram todos os POSTs e replays iniciados, sem outcome
contraditório, violação de replay ou lote Pull acima de `15`. Elas não
qualificaram capacidade/SLA. A primeira obteve mínimo rolling de `1.947 TPS` e
deixou `10.161` outcomes ausentes; a segunda obteve `1.961 TPS` e deixou
`35.675` ausentes. O segundo resultado também demonstra a limitação consciente
do contrato: concluir as obrigações observáveis do warmup corrente não prova
ausência de trabalho interno residual na stack.

O delta de `totalTimeSpent` dos snapshots `jdk.CompilerStatistics` que
delimitam cada janela ativa foi:

| JVM | primeira | estabilizada | limite aceito | resultado |
| --- | ---: | ---: | ---: | --- |
| Kafka Producer | `3,500 s` | `0,155 s` | `1,155 s` | falhou |
| SPI | `2,702 s` | `1,073 s` | `2,073 s` | falhou |
| Notification Gateway | `3,597 s` | `0,920 s` | `1,920 s` | falhou |

Eventos individuais longos de `jdk.Compilation` corroboraram a diferença. A
primeira janela somou `1,738 / 0,732 / 1,836 s` para Producer, SPI e Gateway;
a estabilizada somou `0 / 0,289 / 0,114 s`.

Conclusão: a implementação da fronteira é válida, mas a hipótese específica
`60 s @ 1.000 TPS aquece suficientemente` foi rejeitada. O mecanismo permanece;
os parâmetros precisam de uma nova decisão e validação antes de retomar a Fase
2 híbrida.

#### Tentativa com 120 segundos

Somente o profile `mixed-outcomes-2k-diagnostic` foi alterado para
`1.000 TPS / 120 s`; active, drain, workload e recursos permaneceram iguais. A
primeira tentativa após a recriação da stack parou antes de gerar pagamentos
porque um dos 100 GETs do prewarm HTTP/2 excedeu o timeout de cinco segundos.
A primeira execução funcional seguinte foi
`warmup120-first/20260821_131459`.

O warmup terminou às `13:17:21` e suas obrigações observáveis fecharam em
aproximadamente `29 s`, antes do timeout de `30 s`. Um POST do warmup concluiu
com HTTP `0`, mas recebeu depois o outcome final esperado. Isso expôs e corrigiu
uma inconsistência do gate: conclusão HTTP não-2xx permanece registrada como
violação do run, mas não encerra o gate antes do outcome; outcome ausente ainda
mantém a obrigação pendente e outcome contraditório ainda falha imediatamente.

A janela ativa iniciou `119.741` pagamentos, com mínimo rolling de `1.938 TPS`.
O run não qualificou SLA/capacidade e terminou com `49.190` outcomes ausentes.
Mesmo assim, a janela ativa e os JFRs ficaram completos. Comparando-a com a
referência estabilizada já existente `warmup-gate-stabilized/20260821_035613`,
que possui a mesma carga ativa, os deltas de `jdk.CompilerStatistics` foram:

| JVM | 120 s | referência estabilizada | limite aceito | resultado |
| --- | ---: | ---: | ---: | --- |
| Kafka Producer | `2,330 s` | `0,155 s` | `1,155 s` | falhou |
| SPI | `1,925 s` | `1,073 s` | `2,073 s` | passou |
| Notification Gateway | `2,938 s` | `0,920 s` | `1,920 s` | falhou |

Em relação à primeira execução com 60 segundos, 120 segundos reduziram a
compilação ativa de Producer/SPI/Gateway em aproximadamente `33% / 29% / 18%`,
mas não estabilizaram Producer e Gateway. O recorte temporal reforça que apenas
estender a duração em `1.000 TPS` tem retorno decrescente: no último intervalo
de 30 segundos do warmup, as JVMs gastaram `0,389 / 2,226 / 2,720 s` compilando;
durante o gate, `0,158 / 2,611 / 0,890 s`; ao subir o ativo para `2.000 TPS`, o
custo voltou a `2,330 / 1,925 / 2,938 s`.

A tentativa idêntica seguinte,
`warmup120-stabilized/20260821_131936`, não abriu o active: após o run anterior,
somente `45.799` dos `115.811` pagamentos de warmup tinham outcome final quando
o gate atingiu `30 s`. Portanto ela não é usada como referência nem repetida
automaticamente. O dado disponível indica que o problema restante está mais
ligado à mudança de intensidade de `1k` para `2k` e à capacidade insuficiente
da stack do que a simplesmente acrescentar mais tempo em `1k`. O valor de
`120 s` não é propagado aos outros profiles sem nova decisão.

#### Tentativa com 2.000 TPS por 120 segundos

Para testar a hipótese de que a mudança de intensidade disparava compilação no
active, somente a taxa de warmup do profile diagnóstico foi elevada de
`1.000` para `2.000 TPS`. Duração de `120 s`, active de `2.000 TPS / 60 s`,
drain, workload e recursos permaneceram iguais. A stack e seus volumes foram
recriados antes da única execução `warmup2k120-first/20260821_133043`.

O experimento não chegou à janela ativa. Das `240.000` posições planejadas no
warmup, `178.401` pagamentos originais foram iniciados e `61.599` expiraram sem
carry-over. Todos os POSTs iniciados retornaram HTTP `200`, mas apenas `109.879`
pagamentos possuíam outcome final quando o gate atingiu seu timeout de `30 s`;
restaram `68.522` obrigações de outcome pendentes. O run encerrou com código de
erro e, corretamente, não produziu `run-window.json` nem relatório de SLA.

Sem janela ativa, não existe comparação válida contra o critério de compilação.
Como diagnóstico secundário, usando o primeiro timestamp gerado apenas para
recortar o JFR, a compilação nos últimos 30 segundos do warmup foi
`0,544 / 4,324 / 2,038 s` em Producer/SPI/Gateway; no gate foi
`0 / 1,032 / 1,036 s`. O dado mostra que a taxa maior exercitou o JIT, mas não
resolve a condição mais importante: a stack atual não conclui a própria carga
de warmup de `2.000 TPS` dentro do envelope temporal e do timeout configurado.

Não houve repetição automática nem alteração do timeout naquela tentativa. O
profile oficial de 15 minutos continuou inalterado; a tentativa seguinte
avaliou separadamente um timeout maior somente no diagnóstico.

#### Gate de 120 segundos com warmup de 2.000 TPS

O timeout de conclusão do profile diagnóstico foi elevado isoladamente de
`30 s` para `120 s`, preservando warmup, active, drain, workload e recursos. A
stack e seus volumes foram recriados antes da única execução
`warmup2k120-gate120/20260821_134207`.

A geração do warmup terminou às `13:44:32.293` e o active começou às
`13:46:02.780`: o gate levou `90,487 s` para fechar todas as obrigações
observáveis. Portanto um limite de `90 s` teria expirado por aproximadamente
`0,487 s`; `120 s` forneceu margem sem adicionar espera fixa, pois o gate abriu
assim que o contador chegou a zero.

Com warmup e active na mesma taxa de `2.000 TPS`, a compilação na janela ativa
caiu para:

| JVM | 2k/120s + gate | referência estabilizada | limite aceito | resultado |
| --- | ---: | ---: | ---: | --- |
| Kafka Producer | `1,246 s` | `0,155 s` | `1,155 s` | falhou por `0,091 s` |
| SPI | `1,213 s` | `1,073 s` | `2,073 s` | passou |
| Notification Gateway | `1,175 s` | `0,920 s` | `1,920 s` | passou |

Isso confirma que remover a mudança de intensidade entre warmup e active reduz
materialmente a compilação ativa: em relação a `1k/120s`, Producer/SPI/Gateway
caíram de `2,330 / 1,925 / 2,938 s` para `1,246 / 1,213 / 1,175 s`. O Producer
ficou marginalmente acima do critério predefinido; não há promoção automática.

O run ainda não qualificou capacidade/SLA. A janela ativa iniciou `119.842`
pagamentos, com mínimo rolling de `1.914 TPS`, e terminou com `45.225` outcomes
ausentes. Todos os `316.874` POSTs originais iniciados e os `25.506` replays
foram aceitos, sem outcome contraditório nem violação de replay. O profile
oficial continuou inalterado; uma tentativa posterior avaliou reduzir somente a
taxa do warmup diagnóstico.

#### Warmup de 1.500 TPS por 120 segundos

Para verificar se menos pressão preservaria o aquecimento com menor backlog, a
taxa do warmup diagnóstico foi reduzida isoladamente de `2.000` para
`1.500 TPS`. Duração e timeout de `120 s`, active de `2.000 TPS / 60 s`, drain,
workload e recursos permaneceram iguais. A stack e seus volumes foram recriados
antes da medição `warmup1500-120-gate120-first/20260821_170624`.

A primeira tentativa parou antes de qualquer pagamento porque um GET do prewarm
HTTP/2 excedeu cinco segundos. A medição foi iniciada uma vez na mesma stack;
portanto a única exposição anterior foi o prewarm incompleto do endpoint de
health. Esse segundo prewarm concluiu em três segundos.

O gate fechou em `74,516 s`, redução de `15,970 s` em relação aos `90,487 s` do
warmup de `2.000 TPS`. Foram iniciados `129.872` pagamentos no warmup; `71`
POSTs concluíram com HTTP `0`, mas seus outcomes esperados chegaram antes da
abertura do active. Não houve falha HTTP na janela ativa.

As três JVMs atenderam ao critério predefinido de compilação ativa:

| JVM | warmup 1,5k | warmup 2k | referência estabilizada | limite aceito | resultado |
| --- | ---: | ---: | ---: | ---: | --- |
| Kafka Producer | `0,518 s` | `1,246 s` | `0,155 s` | `1,155 s` | passou |
| SPI | `1,131 s` | `1,213 s` | `1,073 s` | `2,073 s` | passou |
| Notification Gateway | `1,802 s` | `1,175 s` | `0,920 s` | `1,920 s` | passou |

O warmup de `1.500 TPS` preservou o aquecimento necessário pelo critério do JFR
e abriu o active mais cedo. Isso não representa ganho end-to-end: o mínimo
rolling foi `1.933 TPS` e restaram `59.342` outcomes no deadline, contra
`1.914 TPS` e `45.225` outcomes no experimento de `2.000 TPS`. O sampler de
containers também encerrou com uma amostra parcial, fazendo o runner público
sair com código operacional `2`; load-tool, relatório, JFRs e demais artefatos
foram concluídos.

O resultado foi adotado nos profiles diagnóstico e oficial de 15 minutos:
`1.500 TPS / 120 s / 120 s`.

#### Repetição A/B da Fase 1 com a fronteira de warmup corrigida

O A/B da projeção mínima foi repetido depois da correção do load-tool. O A foi
reconstruído a partir de `16481ac` com o diretório `load-test` de `a6fc683`,
preservando a persistência larga e aplicando exatamente o scheduler, warmup e
gate atuais. O B usou `a6fc683`, com `delivery_index`, buffer recente e fallback
SQL. Cada variante recebeu volumes novos, os mesmos recursos e o profile
`mixed-outcomes-2k-diagnostic`.

Os bundles analisados são:

- A: `phase1-ab-a-wide-current-loadtool/20260821_174152`;
- B: `phase1-ab-b-delivery-index-current-loadtool/20260821_175001`.

A primeira tentativa B terminou no prewarm HTTP/2 antes de criar qualquer
pagamento. A única repetição permitida foi feita na mesma stack, sem nova
preparação. Os dois runs medidos terminaram com relatório inválido para SLA,
mas produziram bundles completos e analisáveis.

| sinal | A — delivery larga | B — índice mínimo |
| --- | ---: | ---: |
| gate após a geração do warmup | `87,415 s` | `60,896 s` |
| pagamentos ativos iniciados | `119.743` | `119.634` |
| TPS ativo médio / mínimo / máximo rolling | `1.995,717 / 1.944 / 2.019` | `1.993,900 / 1.840 / 2.019` |
| PACS.002 ativo | `481,400 TPS` | `529,733 TPS` |
| notificações ativas | `596,517 TPS` | `645,517 TPS` |
| outcomes finais / pagamentos iniciados | `211.869 / 272.155` (`77,849%`) | `207.546 / 265.790` (`78,086%`) |
| outcomes ausentes | `60.286` | `58.231` |
| latência p50 / p95 / p99 | `34,463 / 55,269 / 58,673 s` | `31,979 / 54,591 / 55,765 s` |
| CPU média PostgreSQL no ativo | `101,903%` | `102,775%` |
| CPU média Gateway no ativo | `18,633%` | `18,474%` |
| SQL de persistência/leitura do Gateway | `203,292 s` | `66,967 s` |
| WAL desse caminho | `659,459 MB` | `337,973 MB` |
| SQL das 50 queries exportadas | `559,765 s` | `380,971 s` |
| WAL das 50 queries exportadas | `1.965,480 MB` | `1.539,995 MB` |

O objetivo direto da Fase 1 foi confirmado. Mesmo processando somente `1,93%`
menos notificações, o índice mínimo reduziu em `67,06%` o tempo SQL do caminho
de persistência/leitura do Gateway e em `48,75%` seu WAL. No conjunto das 50
queries exportadas, as reduções foram `31,94%` de tempo e `21,65%` de WAL. A
CPU do PostgreSQL continuou saturada porque o trabalho liberado foi consumido
por mais progresso útil: PACS.002 ativo cresceu `10,04%`, notificações ativas
`8,21%`, a proporção de outcomes concluídos aumentou `0,238 pp` e p50/p99
caíram `7,21% / 4,96%`.

O piso de `2.000 TPS` ainda não foi provado. O B teve uma queda concentrada por
volta do segundo ativo `26`: o bucket fixo daquele segundo iniciou `1.870`
pagamentos, enquanto a média ativa ficou apenas `0,09%` abaixo do A e o máximo
rolling foi idêntico. Não houve carry-over nem HTTP diferente de `200` na
janela ativa. O JFR também registrou mais compilação ativa no B (`1,334 / 2,087
/ 1,588 s` em Producer/SPI/Gateway) que no A (`0,374 / 1,216 / 1,103 s`), o que
impede atribuir essa queda isolada ao novo schema. O A/B sustenta manter a Fase
1 pelo ganho direto e pelo progresso end-to-end, mas não qualifica capacidade
nem SLA.

### Fase 2 híbrida — reconciler independente do Kafka

A Fase 2 adiciona ao Notification Gateway uma varredura de
`notification_outbox` sem `delivery_index`. Os resultados usam a mesma
`ensureIndexed` do consumer Kafka; advisory lock, deduplicação por
`communication_id`, alocação de posição por PSP, buffer e sinalização de Pull
permanecem compartilhados pelos dois caminhos. O publisher confiável e todo o
lifecycle `PENDING/PUBLISHED` do SPI continuam inalterados nesta fase.

O reconciler não filtra por status de publicação. Isso cobre tanto o commit que
nunca chegou ao Kafka quanto o evento publicado que não completou a indexação.
Ele executa no startup e depois com `fixedDelay` de um minuto, pagina por
`communication_id` em lotes de `1.000` e reinicia o scan do começo em cada
ciclo. Somente rows com pelo menos um minuto são elegíveis, para que uma
ausência transitória entre o commit da outbox e o consumo Kafka não concorra
com o fast path. Falhas são isoladas por PSP; uma falha global encerra somente
o ciclo corrente.

Não foi adicionada worklist nem watermark persistente. Na base usada para
planejar a mudança, com cerca de `600 mil` rows nas duas tabelas, o anti-join
histórico levou aproximadamente `1,46 s` buscando apenas IDs e `3,23 s`
projetando o payload completo. O MVP aceita esse custo uma vez por minuto e a
maior latência do failure path porque Kafka deve cobrir a operação normal. O
diagnóstico precisa registrar separadamente custo e rows da query; crescimento
material no futuro justifica reavaliar worklist ou batch watermark.

Os testes automatizados cobrem a janela que deixa rows recentes no fast path,
outbox durável sem nenhuma publicação Kafka,
restart com memória vazia, rows `PENDING` e `PUBLISHED`, paginação sem cursor
durável, falha parcial por PSP e corrida Kafka/reconciler produzindo exatamente
um índice e uma posição.

O primeiro diagnóstico limpo, antes da janela de idade, mostrou por que essa
fronteira é necessária: o reconciler tomou `953` lacunas transitórias do Kafka
(`489 + 117 + 347`) como candidatos de recovery. Após exigir um minuto de
idade, o run limpo
`phase2-reconciler-grace-clean/20260821_185040` registrou zero recovery, zero
lacuna ao final (`620.201` outbox e `620.201` índices) e nenhuma violação de
replay ou Pull. As quatro varreduras saudáveis retornaram zero rows, mas ainda
consumiram `14,741 s` de SQL wall-time (`3,685 s` médio, `8,418 s` máximo),
confirmando que o custo histórico precisa permanecer visível.

O mesmo run iniciou `119.920` pagamentos ativos (`1.998,667 TPS` médio,
rolling mínimo/máximo `1.945 / 2.020`), concluiu `217.280` outcomes e terminou
inválido para SLA, com `54.419` outcomes ausentes e p50/p95/p99 de
`24,377 / 53,867 / 56,746 s`. Comparado ao baseline da Fase 1, houve mais
progresso útil e menor p50, mas esta execução isolada não atribui essa diferença
ao reconciler nem qualifica a capacidade de `2.000 TPS`.

### Fase 3 híbrida — notificação imutável e Kafka best effort

A Fase 3 remove do SPI o lifecycle de publicação que deixou de participar da
correctness depois da introdução do reconciler. A tabela
`notification_outbox` foi migrada para `outbound_notification`, preservando
somente a identidade, o destinatário, os metadados, o payload imutável e o
instante de criação. Foram removidos `publication_status`, `attempt_count`,
`next_attempt_at`, `last_error`, `published_at`, `updated_at`, constraints e o
índice de pendências associados exclusivamente a `PENDING/PUBLISHED`.

O insert da notificação continua na mesma transação financeira e retorna apenas
as rows realmente novas. Depois do commit, o SPI inicia uma publicação Kafka
assíncrona para cada uma delas e não espera o ACK do broker. Falha síncrona,
falha futura ou crash não altera a row, não abre retry no SPI e não muda o
resultado financeiro já commitado; o reconciler de um minuto passa a ser o
caminho durável que converge qualquer notificação sem `delivery_index`. Kafka
permanece como fast path de baixa latência para alimentar o buffer do Gateway.

O Gateway passou a consultar e reconciliar diretamente
`outbound_notification`. O protocolo Pull, o limite de 15, o cursor por PSP, o
índice mínimo, o buffer em memória e o fallback SQL permanecem inalterados. A
migração é coordenada para o MVP: não existe dual-read, dual-write ou suporte a
rolling upgrade entre os dois schemas.

O smoke funcional
`phase3-outbound-notification-smoke/20260821_193636` concluiu os `1.250` POSTs,
os `1.000` PACS.002 e as `3.250` notificações sem outcome ausente ou
contraditório, sem violação de replay/Pull e com latência máxima de `358,573
ms`. O relatório foi inválido exclusivamente porque o rolling mínimo observado
foi `99 TPS` para o piso de `100 TPS`; esse gate não foi relaxado para acomodar
o smoke.

O primeiro início do diagnóstico falhou ainda no prewarm HTTP/2, antes de gerar
qualquer pagamento. A repetição na mesma stack já aquecida produziu o bundle
`phase3-outbound-notification/20260821_193949`. Todos os `261.871` pagamentos
originais tiveram HTTP aceito, sem outcome contraditório e sem violação de
replay ou Pull. O run não qualificou capacidade nem SLA: iniciou `119.473`
pagamentos ativos (`1.991,217 TPS` médio, rolling mínimo/máximo `1.878 / 2.020`),
teve `49` PACS.002 sem HTTP aceito, terminou com `23.598` outcomes ausentes e
p50/p95/p99 de `22,973 / 44,071 / 49,001 s`.

A comparação informativa com a Fase 2
`phase2-reconciler-grace-clean/20260821_185040` confirmou o efeito local da
mudança. O update de publicação caiu de `427` chamadas, `27,703 s` de SQL,
`181.978` rows e `152.467.009 B` de WAL para zero. O WAL das 50 queries
exportadas caiu `10,461%` (`1.590.643.301 B → 1.424.244.110 B`), apesar de mais
trabalho útil atravessar o pipeline. O insert imutável gravou `19,216%` menos
WAL por row que o insert antigo, embora seu tempo SQL por row tenha sido
`3,806%` maior nesta execução; ele passou a ser a query dominante e não deve
ser confundido com o custo removido do lifecycle.

O progresso end-to-end também aumentou: PACS.002 ativo cresceu `30,794%`,
notificações ao pagador `27,604%`, outcomes concluídos `9,662%` e a proporção
concluída no deadline passou de `79,971%` para `90,989%`. Outcomes ausentes
caíram `56,636%`; p50/p95/p99/max caíram respectivamente `5,759% / 18,186% /
13,650% / 13,614%`. O PostgreSQL continuou saturado durante o active
(`102,983%` médio contra `101,622%` na Fase 2), e o tempo SQL agregado aumentou
porque o sistema conseguiu executar mais trabalho downstream.

Após o run, PostgreSQL continha exatamente `646.065` rows em
`outbound_notification` e `646.065` em `delivery_index`, com zero notificação
sem índice e zero índice sem fonte. O perfil SQL não contém
`publication_status`, update de `outbound_notification` nem consulta ao schema
antigo. Isso comprova a remoção do lifecycle e a convergência final, mas o piso
sustentado de `2.000 TPS` e o SLA de um segundo permanecem abertos para o
trabalho de estabilização.

### Batching de mensagens de saída por destinatário

O SPI passou a agrupar, dentro da própria transação de processamento, os itens
de notificação por PSP destinatário e a emitir mensagens consecutivas de no
máximo 15 itens. `pacs.008` de aceite é agrupado pelo recebedor; resultados
`pacs.002` são agrupados pelo destinatário e podem misturar `ACSC`, `ACCC` e
`RJCT`, pois o status permanece no item. A ordem de entrada é preservada dentro
de cada grupo.

Cada mensagem recebe um UUID que é usado tanto em `GrpHdr.MsgId` quanto em
`communication_id`. A transição financeira efetivamente adquirida continua
sendo a única autoridade para criar itens. A outbox deixou de implementar uma
segunda idempotência com `ON CONFLICT`: o insert é estrito, e sua falha reverte
pagamento/status, saldo, auditoria e notificação na transação corrente.

O schema de `outbound_notification` foi reduzido a
`communication_id`, `recipient_ispb`, `payload` e `created_at`. Kafka carrega o
destinatário como chave, o payload completo como value e somente
`notification.communication-id` como header. O Gateway e o reconciler usam o
mesmo contrato mínimo. O protocolo Pull permanece limitado a 15 mensagens; uma
resposta pode, portanto, conter até 225 itens de negócio.

O smoke limpo
`outbound-notification-batching-smoke/20260821_231905` concluiu `1.250/1.250`
POSTs e todos os `1.250` outcomes, sem contradição nem violação de replay/Pull.
O p99 foi `247,247 ms`. O relatório saiu inválido apenas porque o rolling mínimo
do smoke foi `99 TPS` para alvo de `100 TPS`.

O diagnóstico B limpo
`outbound-notification-batched-15/20260821_232146` foi comparado ao baseline
pre-batching
`delivery-index-strict-insert-fallback/20260821_215806`, com o mesmo profile,
recursos e instrumentação:

| sinal | baseline — uma mensagem por item | B — até 15 itens | variação |
| --- | ---: | ---: | ---: |
| rows em `outbound_notification` | `648.923` | `142.524` | `-78,037%` |
| tempo SQL do insert da outbox | `146,211 s` | `13,483 s` | `-90,779%` |
| WAL do insert da outbox | `613.432.099 B` | `136.471.982 B` | `-77,753%` |
| tempo SQL do insert em `delivery_index` | `83,520 s` | `9,280 s` | `-88,889%` |
| WAL do insert em `delivery_index` | `362.110.006 B` | `57.931.319 B` | `-84,002%` |
| tempo SQL agregado das 50 queries | `428,799 s` | `217,843 s` | `-49,197%` |
| WAL agregado das 50 queries | `1.435.220.438 B` | `698.588.931 B` | `-51,325%` |
| PACS.002 ativo | `908,483 TPS` | `1.563,883 TPS` | `+72,142%` |
| notificações ao pagador no ativo | `1.105,600 TPS` | `1.951,333 TPS` | `+76,495%` |
| outcomes finais | `242.877` | `273.969` | `+12,802%` |
| outcomes ausentes | `14.118` | `0` | — |
| latência p50 / p99 | `19,854 / 46,036 s` | `1,153 / 2,808 s` | `-94,193% / -93,900%` |
| TPS médio / mínimo rolling | `1.993,983 / 1.924` | `1.992,283 / 1.900` | `-0,085% / -1,247%` |
| CPU média PostgreSQL no ativo | `100,209%` | `99,514%` | `-0,695 pp` |

As `142.524` mensagens persistidas carregaram exatamente `712.321` itens:
média `4,998`, p50 `2`, p95 `15` e máximo `15`. Nenhum
`communication_id` divergiu de `GrpHdr.MsgId`. Ao final, havia exatamente
`142.524` rows tanto na outbox quanto no índice, sem notificação não indexada
nem índice órfão.

O efeito local e o progresso downstream melhoraram de forma material, mas o B
ainda não prova a meta: o mínimo rolling ficou em `1.900 TPS`, e p50/p95/p99
continuaram acima do SLA de `1 s` (`1,153 / 2,328 / 2,808 s`). O PostgreSQL
permaneceu praticamente saturado; o próximo perfil deve partir das novas
queries dominantes, não do custo de persistência de uma mensagem por item que
esta mudança removeu.

### Microbenchmark do tamanho do batch de admissão PACS.008

Antes de alterar o consumer Kafka, o insert de admissão foi medido isoladamente
com batches de `50`, `100`, `200` e `500` entradas. O schema scratch reproduziu
a tabela larga vigente, primary key e `fillfactor=50`, partindo das `273.969`
rows observadas no último diagnóstico. Cada variante recebeu duas rodadas de
`100.000` entradas com `5%` de conflitos, em ordens inversas; cada batch usou
os arrays JDBC, o `INSERT ... ON CONFLICT DO NOTHING RETURNING payment_id` e um
commit próprios. O ResultSet foi integralmente consumido.

| batch | transações nas duas rodadas | inputs/s | parede/input | CPU/input | WAL/input |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `50` | `4.000` | `19.996` | `50,010 us` | `26,050 us` | `368,520 B` |
| `100` | `2.000` | `29.175` | `34,275 us` | `21,200 us` | `368,340 B` |
| `200` | `1.000` | `36.373` | `27,493 us` | `19,050 us` | `368,072 B` |
| `500` | `400` | `35.612` | `28,081 us` | `18,900 us` | `368,179 B` |

O custo fixo por transação é material: de `50` para `200`, o throughput do
statement cresceu `81,90%`, o tempo de parede por entrada caiu `45,03%` e a CPU
por entrada caiu `26,87%`. Já `500` não melhorou o resultado de parede: ficou
`2,09%` abaixo de `200`, com somente `0,79%` menos CPU por entrada. O WAL ficou
praticamente constante porque a quantidade de rows persistidas não mudou.

O resultado não justifica forçar o limite máximo de `500`. O próximo candidato
deve preservar `max.poll.records=500`, separar a configuração do consumer
PACS.008 da configuração de PACS.002 e buscar batches efetivos próximos de
`200` por meio de `fetch.min.bytes` e `fetch.max.wait.ms`. A distribuição real
dos lotes no listener e o A/B end-to-end decidirão se a economia local libera
capacidade do PostgreSQL sem custo de latência desproporcional. O schema
scratch foi removido e a tabela operacional permaneceu com `273.969` rows.

O candidato implementado cria factories independentes. PACS.008 mantém
`max.poll.records=500`, passa a `fetch.min.bytes=131.072` e
`fetch.max.wait.ms=100`; em `2.000 TPS`, o limite temporal deve formar cerca de
`200` registros quando o mínimo em bytes não for atingido antes. Naquele A/B,
PACS.002 ainda preservava `500 / 1.024 / 10 ms`, pois adicionar espera à
transição de settlement não participava da hipótese. Esses números são
candidatos experimentais, não um novo contrato de negócio. O próximo
diagnóstico deve registrar a cardinalidade real recebida pelo listener
PACS.008 e comparar throughput, cauda, CPU, SQL e WAL com
`outbound-notification-batched-15/20260821_232146`.

### A/B end-to-end do batching de admissão PACS.008

O diagnóstico limpo do candidato está em
`pacs008-kafka-batch-200/20260822_012325`. Ele foi executado uma única vez após
recriar a stack e os volumes, com o mesmo profile e instrumentação do baseline
`outbound-notification-batched-15/20260821_232146`. O container confirmou os
parâmetros `500 / 131.072 / 100 ms` para PACS.008 e `500 / 1.024 / 10 ms` para
PACS.002.

O tamanho efetivo médio foi reconstruído pelo total de pagamentos criados mais
os conflitos reclassificados, dividido pelas chamadas do insert. O workload
não contém payload inválido ou não autorizado; portanto essa soma representa
os candidatos válidos recebidos pelo listener, incluindo os replays.

| admissão PACS.008 | baseline | candidato | variação |
| --- | ---: | ---: | ---: |
| batch efetivo médio | `90,833` | `197,842` | `+117,809%` |
| chamadas do insert | `3.167` | `1.468` | `-53,647%` |
| pagamentos criados | `273.969` | `276.602` | `+0,961%` |
| conflitos reclassificados | `13.698` | `13.830` | `+0,964%` |
| tempo SQL do insert | `49,132 s` | `44,832 s` | `-8,750%` |
| lock de saldos do pagador | `2.252` chamadas | `1.272` chamadas | `-43,517%` |
| aplicação de débitos | `2.214` chamadas | `1.267` chamadas | `-42,773%` |
| rejeição por fundos insuficientes | `1.971` chamadas | `1.228` chamadas | `-37,697%` |

O mecanismo medido no spike apareceu no pipeline real: o batch médio ficou a
apenas `1,079%` do alvo experimental de `200`, e a redução de transações se
propagou para saldo e rejeição. O insert processou mais rows com `8,750%` menos
tempo SQL acumulado.

| resultado end-to-end | baseline | candidato | variação |
| --- | ---: | ---: | ---: |
| TPS médio / mínimo rolling | `1.992,283 / 1.900` | `1.994,850 / 1.925` | `+0,129% / +1,316%` |
| PACS.002 ativo | `1.563,883 TPS` | `1.579,733 TPS` | `+1,014%` |
| notificações ao pagador no ativo | `1.951,333 TPS` | `1.968,450 TPS` | `+0,877%` |
| outcomes finais / ausentes | `273.969 / 0` | `276.602 / 0` | — |
| outcomes ativos dentro de `1 s` | `39.298` (`32,875%`) | `86.655` (`72,399%`) | `+39,524 pp` |
| latência p50 / p95 | `1.152,972 / 2.327,797 ms` | `839,264 / 1.683,226 ms` | `-27,209% / -27,690%` |
| latência p99 / máxima | `2.808,128 / 3.332,072 ms` | `2.005,423 / 2.558,075 ms` | `-28,585% / -23,229%` |
| CPU média PostgreSQL | `99,514%` | `98,982%` | `-0,532 pp` |
| SQL agregado das 50 queries | `217,843 s` | `202,476 s` | `-7,054%` |
| WAL agregado das 50 queries | `698.588.931 B` | `704.382.461 B` | `+0,829%` |

O aumento pequeno de WAL acompanha `0,961%` mais pagamentos e mais progresso
downstream; não apareceu uma nova amplificação por row. PostgreSQL continuou
saturado e executável na maior parte das amostras ativas, mas o mesmo minuto
ativo concluiu todo o workload com cauda muito menor.

O run ainda não qualifica a meta: o mínimo rolling ficou em `1.925 TPS`, abaixo
do piso contínuo de `2.000`, e o p99 permaneceu em `2,005 s`, acima do SLA de
`1 s`. Não houve HTTP rejeitado, outcome ausente ou contraditório, nem violação
de replay/Pull. A evidência sustenta a configuração como candidata vigente; a
decisão final e eventuais ajustes posteriores permanecem explícitos, sem
remoção automática do código.

### Microbenchmark do fingerprint binário

O armazenamento atual do fingerprint (`VARCHAR(64)` hexadecimal e versão
`VARCHAR(16)`) foi comparado a `BYTEA` com exatamente 32 bytes e versão
`SMALLINT`. Duas tabelas scratch reproduziram o schema, primary key,
cardinalidade observada de `276.602` rows e `fillfactor=50`. Cada variante
processou quatro rodadas de `100.000` entradas em batches de `200`, com `5%` de
conflitos, ordem alternada, commit por batch e consumo integral do `RETURNING`.

O preparo Java calculou SHA-256 de verdade nas duas variantes. A vigente ainda
converteu os 32 bytes para 64 caracteres hexadecimais; a candidata entregou os
bytes diretamente ao array JDBC. Os tempos agregados das quatro rodadas foram:

| sinal isolado | texto hexadecimal | binário | variação |
| --- | ---: | ---: | ---: |
| preparo Java | `970,347 ms` | `868,395 ms` | `-10,507%` |
| JDBC + statement + commit | `13.938,806 ms` | `12.650,637 ms` | `-9,242%` |
| tempo total | `14.909,153 ms` | `13.519,032 ms` | `-9,324%` |
| throughput | `26.829 inputs/s` | `29.588 inputs/s` | `+10,283%` |
| CPU do backend PostgreSQL | `9.000 ms` | `8.720 ms` | `-3,111%` |
| WAL por input | `368,443 B` | `338,016 B` | `-8,258%` |

A variante binária melhorou o tempo total em três das quatro rodadas, mas houve
ruído material: o ganho pareado variou de uma regressão de `20,365%` a uma
melhora de `29,175%`. Por isso, os `10,283%` agregados caracterizam o candidato
isolado, não uma previsão direta para o end-to-end. A queda de CPU de apenas
`3,111%` é a estimativa mais conservadora do trabalho que pode ser liberado no
PostgreSQL saturado.

| armazenamento após as rodadas | texto hexadecimal | binário | variação |
| --- | ---: | ---: | ---: |
| bytes médios por row | `171,995 B` | `140,000 B` | `-18,602%` |
| heap | `254.918.656 B` | `207.716.352 B` | `-18,517%` |
| índice | `49.496.064 B` | `49.496.064 B` | `0%` |
| relação total | `304.513.024 B` | `257.302.528 B` | `-15,504%` |

A redução física e de WAL é inequívoca, e o ganho isolado de throughput torna
o fingerprint binário um candidato plausível para implementação e A/B. O
benefício sistêmico esperado deve permanecer moderado: primary key, commits e
demais campos não encolhem, e a CPU do executor não caiu na mesma proporção que
a heap. O schema scratch foi removido; a tabela operacional permaneceu com
`276.602` rows e nenhum código de produção foi alterado pelo spike.

### Microbenchmark da compactação de status e motivo

O armazenamento vigente de `status` (`VARCHAR(50)`) e `rejection_reason`
(`TEXT`) foi comparado a dois códigos `SMALLINT`. As tabelas scratch
reproduziram o restante do schema, primary key, `fillfactor=50` e os `276.602`
pagamentos existentes. Cada variante processou quatro rodadas de `100.000`
entradas em batches de `200`, com `5%` de conflitos. Em cada batch, os novos
pagamentos foram inseridos como `WAITING_ACCEPTANCE`, exatamente `20%` foram
atualizados para `REJECTED / INSUFFICIENT_FUNDS`, e então ocorreu o commit.

| sinal isolado | texto | códigos compactos | variação |
| --- | ---: | ---: | ---: |
| JDBC + statements + commits | `14.329,596 ms` | `12.262,535 ms` | `-14,425%` |
| throughput | `27.914 inputs/s` | `32.620 inputs/s` | `+16,857%` |
| CPU do backend PostgreSQL | `8.750 ms` | `8.510 ms` | `-2,743%` |
| WAL por input | `364,137 B` | `344,319 B` | `-5,442%` |

Os códigos compactos venceram no tempo de parede em três das quatro rodadas.
O ganho pareado de throughput variou de uma regressão de `3,020%` a uma
melhora de `27,663%`; por isso, os `16,857%` agregados caracterizam apenas o
microbenchmark. A redução de CPU de `2,743%` é a estimativa conservadora do
trabalho que essa representação pode liberar no PostgreSQL saturado.

| armazenamento após as rodadas | texto | códigos compactos | variação |
| --- | ---: | ---: | ---: |
| bytes médios por row | `177,326 B` | `159,648 B` | `-9,969%` |
| heap | `249.405.440 B` | `228.073.472 B` | `-8,553%` |
| índice | `29.212.672 B` | `29.212.672 B` | `0%` |
| relação total | `278.708.224 B` | `257.368.064 B` | `-7,657%` |

O ganho físico e de WAL é consistente, mas o efeito direto sobre a CPU foi
moderado e semelhante ao observado no fingerprint binário. A compactação é um
candidato plausível para implementação e A/B, não um gargalo dominante já
demonstrado. O spike mediu somente `payment_transaction_entity`; aplicar a
mesma representação à auditoria seria uma mudança adicional. O schema scratch
foi removido, a tabela operacional permaneceu com `276.602` rows e nenhum
código de produção foi alterado.

### Microbenchmark das categorias da auditoria

A auditoria append-only vigente, com `event_type`, `previous_status`,
`resulting_status` e `reason` textuais, foi comparada a duas representações:
códigos `SMALLINT` e tipos `ENUM` do PostgreSQL. As três tabelas scratch
reproduziram as constraints, os três índices, a cardinalidade inicial de
`719.166` eventos e a distribuição real entre criação aceita, criação
rejeitada, mudança de status e settlement.

Cada variante processou seis rodadas de `100.000` eventos em `500` commits de
`200`. A ordem formou um quadrado latino: cada representação executou primeira,
intermediária e última exatamente duas vezes. Isso evita atribuir à
representação o aquecimento de cache dentro da rodada.

| sinal isolado | texto | `SMALLINT` | `ENUM` PostgreSQL |
| --- | ---: | ---: | ---: |
| JDBC + statements + commits | `12.666,889 ms` | `11.090,981 ms` (`-12,441%`) | `11.218,770 ms` (`-11,432%`) |
| throughput mediano | `51.039 eventos/s` | `54.409 eventos/s` (`+6,602%`) | `53.635 eventos/s` (`+5,086%`) |
| CPU agregada do backend | `7.430 ms` | `7.050 ms` (`-5,114%`) | `7.110 ms` (`-4,307%`) |
| CPU mediana por rodada | `1.185 ms` | `1.150 ms` (`-2,954%`) | `1.185 ms` (`0%`) |
| WAL por evento | `454,204 B` | `419,021 B` (`-7,746%`) | `421,350 B` (`-7,233%`) |

A quinta rodada textual consumiu `2.630,076 ms` de parede, mas somente
`1.150 ms` de CPU do backend. Esse outlier externo ao executor PostgreSQL
infla o throughput agregado; por isso, a comparação de parede usa a mediana.
`SMALLINT` apresentou o melhor sinal de CPU e venceu o tempo de parede em todas
as seis rodadas. `ENUM` venceu cinco, preservando os nomes nas consultas.

| armazenamento após as rodadas | texto | `SMALLINT` | `ENUM` PostgreSQL |
| --- | ---: | ---: | ---: |
| bytes médios por row | `145,440 B` | `110,189 B` (`-24,237%`) | `114,110 B` (`-21,542%`) |
| heap | `199.368.704 B` | `152.461.312 B` (`-23,528%`) | `157.294.592 B` (`-21,104%`) |
| índices | `205.979.648 B` | `205.979.648 B` (`0%`) | `205.979.648 B` (`0%`) |
| relação total | `405.422.080 B` | `358.506.496 B` (`-11,572%`) | `363.339.776 B` (`-10,380%`) |

A compactação da auditoria oferece um sinal de CPU mais forte que as duas
compactações isoladas da payment row e uma redução física relevante, porque
cada pagamento produz múltiplos eventos. `SMALLINT` maximiza o ganho;
`ENUM` preserva a legibilidade operacional e retém quase todo o benefício de
WAL e armazenamento, mas não melhorou a CPU mediana. O schema scratch foi
removido, `payment_audit_event` permaneceu com `719.166` rows e nenhum código
de produção foi alterado.

### Compactação conjunta de pagamentos e auditoria

A implementação reuniu os três candidatos em uma única migração. O fingerprint
SHA-256 passou de hexadecimal textual para `BYTEA(32)` validado por constraint,
e sua versão passou de `v1` textual para `SMALLINT`. Status, motivo de rejeição
e categorias da auditoria passaram a usar tipos `ENUM` do PostgreSQL com os
mesmos nomes dos enums Java. A escolha preserva consultas operacionais legíveis
e evita uma tabela de tradução de códigos no tooling de diagnóstico.

A migração converte dados existentes e recria constraints e índices parciais
da auditoria sobre os novos tipos. Um teste dedicado executa V1--V13, persiste
rows no formato legado, aplica V14 e comprova a preservação do fingerprint,
versão, status, tipo de evento e motivo. A suíte completa do SPI passou com
`201` testes, sem falha ou erro.

O diagnóstico B está em
`compact-payment-audit-storage/20260822_021728`. O baseline comparável é
`pacs008-kafka-batch-200/20260822_012325`; ambos usam o mesmo profile,
recursos, instrumentação e batching PACS.008. O B iniciou `275.802` pagamentos
e produziu todos os outcomes, sem ausência, contradição ou violação de replay e
Pull. Um POST de warmup excedeu o timeout de `5 s` e recebeu status observável
`0`, embora o pagamento tenha sido processado e seu outcome tenha chegado; por
isso o relatório contém uma violação HTTP funcional que não pertence à janela
ativa.

| persistência | baseline | compactada | variação |
| --- | ---: | ---: | ---: |
| insert de pagamentos — rows | `276.602` | `275.802` | `-0,289%` |
| insert de pagamentos — SQL | `44,832 s` | `38,943 s` | `-13,137%` |
| insert de pagamentos — SQL/row | `162,083 us` | `141,198 us` | `-12,885%` |
| insert de pagamentos — WAL/row | `392,986 B` | `341,547 B` | `-13,089%` |
| insert da auditoria — rows | `719.166` | `717.086` | `-0,289%` |
| insert da auditoria — SQL | `55,178 s` | `49,411 s` | `-10,451%` |
| insert da auditoria — SQL/row | `76,724 us` | `68,906 us` | `-10,191%` |
| insert da auditoria — WAL/row | `468,709 B` | `437,386 B` | `-6,683%` |
| 23 queries exportadas — SQL | `202,476 s` | `194,314 s` | `-4,031%` |
| 23 queries exportadas — WAL | `704.382.461 B` | `652.856.482 B` | `-7,315%` |

O efeito físico apareceu no pipeline real: os dois inserts diretamente
afetados ficaram mais baratos por row, e o WAL total caiu apesar de o workload
útil ter permanecido praticamente igual. A transição aceita de pagamentos foi
o sinal contrário: seu WAL por row caiu `32,393%`, mas o tempo SQL subiu de
`6,312 s` para `17,452 s`. As amostras de atividade mostram o backend
executável, sem lock ou I/O dominante; retirando somente esse statement, o
tempo agregado das demais queries caiu `9,840%`. Portanto, a run confirma a
compactação física, mas não atribui a variação de parede dessa transição à
representação dos enums.

| resultado end-to-end | baseline | compactada | variação |
| --- | ---: | ---: | ---: |
| TPS médio / mínimo rolling | `1.994,850 / 1.925` | `1.995,683 / 1.914` | `+0,042% / -0,571%` |
| latência p50 / p95 | `839,264 / 1.683,226 ms` | `831,572 / 1.488,426 ms` | `-0,917% / -11,573%` |
| latência p99 / máxima | `2.005,423 / 2.558,075 ms` | `1.730,257 / 2.080,070 ms` | `-13,721% / -18,686%` |
| outcomes ativos dentro de `1 s` | `86.655` (`72,399%`) | `85.871` (`71,714%`) | `-0,685 pp` |
| CPU média PostgreSQL no ativo | `98,982%` | `101,358%` | `+2,376 pp` |

A cauda melhorou, mas a fração dentro de `1 s`, o mínimo rolling e a CPU não.
Logo, esta única comparação não demonstra uma redução sistêmica uniforme nem
qualifica a meta: o piso contínuo de `2.000 TPS` e o p99 de `1 s` continuam
abertos. O código permanece disponível para a decisão explícita do projeto;
nenhuma reversão automática foi feita a partir do resultado misto.

### Microbenchmark do índice geral da auditoria

O custo de `idx_payment_audit_event_payment (payment_id, event_id)` foi isolado
em duas tabelas scratch. Ambas preservaram a primary key, os índices únicos de
`PAYMENT_CREATED` e `SETTLEMENT_APPLIED`, enums, constraints e schema da tabela
operacional; somente a variante B não possuía o índice geral usado para leitura
do histórico.

Cada tabela começou com os mesmos `717.086` eventos e a mesma distribuição do
último diagnóstico. Foram executadas seis rodadas alternadas por variante,
cada uma com `100.000` eventos, `500` commits e batches de `200`. O cliente
`pgbench` executou fora do cgroup do PostgreSQL; CPU acumulada mediu somente o
container servidor. O scratch foi removido após a coleta e os componentes
parados para isolamento foram reiniciados.

| sinal isolado | com índice geral | sem índice geral | variação |
| --- | ---: | ---: | ---: |
| throughput agregado | `49.219 eventos/s` | `58.924 eventos/s` | `+19,718%` |
| parede por evento | `20,317 us` | `16,971 us` | `-16,470%` |
| CPU PostgreSQL por evento | `11,543 us` | `8,930 us` | `-22,633%` |
| WAL estável por evento | `374,643 B` | `269,230 B` | `-28,137%` |

A variante sem índice venceu as seis rodadas em parede e CPU. A primeira
rodada de WAL de cada tabela foi excluída somente da razão estável por conter
full-page images de primeiro toque; as cinco rodadas seguintes ficaram
consistentes. Ao final, cada tabela continha exatamente `1.319.086` eventos. A
heap era `134 MB` em ambas; os índices ocupavam `134 MB` com o índice geral e
`65 MB` sem ele. O próprio índice removido ocupava `68 MB`.

O resultado demonstra que esse índice de leitura é um custo material no hot
path append-only. Ele não participa das invariantes de unicidade: primary key e
os dois índices parciais de correctness permanecem. A remoção é candidata a um
A/B end-to-end se consulta online do histórico completo por `payment_id` não
for requisito; exportações integrais por run continuam naturalmente orientadas
a leitura sequencial.

### Microbenchmark da PK técnica da auditoria

O ganho incremental de remover a primary key de `event_id` foi medido depois
da remoção do índice geral. As duas tabelas scratch mantiveram os índices
únicos parciais de criação e settlement. A variante B removeu somente a
constraint/índice da PK; `event_id` permaneceu
`BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL`.

O preparo e a carga repetiram a cardinalidade, distribuição, batches e ordem
alternada do spike anterior: `717.086` eventos iniciais e seis rodadas de
`100.000`, com `500` commits de `200` eventos por variante.

| sinal isolado | com PK | identity `NOT NULL`, sem PK | variação |
| --- | ---: | ---: | ---: |
| throughput agregado | `58.241 eventos/s` | `63.670 eventos/s` | `+9,321%` |
| parede por evento | `17,170 us` | `15,706 us` | `-8,526%` |
| CPU PostgreSQL por evento | `9,285 us` | `7,829 us` | `-15,683%` |
| WAL estável por evento | `269,356 B` | `203,133 B` | `-24,586%` |

A variante sem PK venceu as seis rodadas em parede e CPU. Com `1.319.086`
eventos em cada tabela, a heap permaneceu em `134 MB`; os índices caíram de
`65 MB` para `37 MB`, e o total da relação de `199 MB` para `171 MB`. O índice
da PK ocupava `28 MB`. Todos os `event_id` gerados no spike foram não nulos e
distintos.

O resultado demonstra o custo da identidade declarativa, mas não equivale a
uma garantia de unicidade. `IDENTITY NOT NULL` rejeita o insert ordinário sem
valor gerado e a aplicação não fornece `event_id`; ainda assim, reset
administrativo da sequence ou `OVERRIDING SYSTEM VALUE` pode criar duplicata
sem a PK. As invariantes de negócio de criação e settlement continuam nos dois
índices únicos parciais. O scratch foi removido e nenhuma mudança foi aplicada
ao schema operacional.

### Aplicação da compactação dos índices da auditoria

A migration V15 aplicou em conjunto os dois candidatos medidos: removeu
`idx_payment_audit_event_payment` e a primary key técnica de `event_id`. A
coluna permaneceu `BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL`, e os índices
únicos parciais `uq_payment_audit_created` e
`uq_payment_audit_settlement` continuaram protegendo as invariantes de negócio.
O teste de migration parte de V14 com um evento persistido e comprova que V15
preserva seu `event_id`; os testes de schema comprovam a ausência da PK e do
índice geral. A suíte completa do SPI passou com `204` testes.

O diagnóstico B está em
`audit-index-compaction/20260822_135027`. O baseline comparável permanece
`compact-payment-audit-storage/20260822_021728`: a V15 é a única alteração no
caminho exercitado, e profile, recursos, instrumentação, reset da stack e
parâmetros do workload foram preservados. Após o B, a tabela operacional tinha
`673.210` eventos, heap de `79 MB` e somente `30 MB` nos dois índices parciais.

| custo físico | baseline | sem índices técnicos | variação |
| --- | ---: | ---: | ---: |
| auditoria — rows | `717.086` | `673.210` | `-6,119%` |
| auditoria — SQL/row | `68,906 us` | `58,190 us` | `-15,552%` |
| auditoria — WAL/row | `437,387 B` | `234,686 B` | `-46,344%` |
| 23 queries exportadas — SQL | `194,314 s` | `209,937 s` | `+8,040%` |
| 23 queries exportadas — WAL | `652.856.482 B` | `437.062.413 B` | `-33,054%` |

O efeito local previsto apareceu: o insert da auditoria ficou mais barato e o
WAL total caiu. Esse ganho não se traduziu em melhora end-to-end nesta execução.
O PostgreSQL continuou saturado e outros statements ficaram mais caros por row,
incluindo o insert de pagamento (`+48,302%`), o select PACS.002 com lock
(`+18,516%`) e o insert da outbound notification (`+70,832%`). Isso demonstra
redistribuição do tempo do PostgreSQL na run, mas uma única comparação não
atribui a variação desses statements à remoção dos índices.

| resultado end-to-end | baseline | sem índices técnicos | variação |
| --- | ---: | ---: | ---: |
| TPS médio / mínimo rolling | `1.995,683 / 1.914` | `1.989,017 / 1.903` | `-0,334% / -0,575%` |
| latência p50 / p95 | `831,572 / 1.488,426 ms` | `1.068,209 / 1.706,421 ms` | `+28,457% / +14,646%` |
| latência p99 / máxima | `1.730,257 / 2.080,070 ms` | `2.050,477 / 2.636,731 ms` | `+18,507% / +26,762%` |
| outcomes ativos dentro de `1 s` | `85.871` (`71,714%`) | `47.209` (`39,558%`) | `-32,156 pp` |
| CPU média PostgreSQL no ativo | `101,358%` | `101,076%` | `-0,282 pp` |

O B iniciou todos os `119.341` pagamentos ativos sem carga fora da janela e não
teve erro HTTP ativo, outcome ausente/contraditório nem violação de replay ou
Pull. Houve `17` timeouts HTTP observáveis somente no warmup, contra um no
baseline; seus pagamentos foram processados e tiveram outcome, mas essas
violações funcionais e o piso rolling abaixo de `2.000 TPS` mantiveram
`valid: false`. O código permanece no worktree para decisão explícita do
projeto; o benchmark não acionou reversão automática.

#### Repetição limpa da variante sem índices técnicos

Uma única repetição B foi executada para verificar se a regressão ampla do
primeiro B era reproduzível. A stack e os volumes foram recriados, e código,
profile, recursos e instrumentação permaneceram inalterados. O bundle está em
`audit-index-compaction-repeat/20260822_141246`.

O B2 aceitou todos os `275.304` POSTs, sem timeout ou outra violação funcional,
e produziu todos os outcomes esperados, sem ausência, contradição ou violação
de replay/Pull. O gate de warmup levou aproximadamente `10,4 s`, praticamente o
mesmo tempo observado no baseline e no B1.

| custo físico | baseline | B1 | B2 | B2 vs. baseline |
| --- | ---: | ---: | ---: | ---: |
| auditoria — SQL/row | `68,906 us` | `58,190 us` | `42,548 us` | `-38,252%` |
| auditoria — WAL/row | `437,387 B` | `234,686 B` | `235,552 B` | `-46,146%` |
| 23 queries exportadas — SQL | `194,314 s` | `209,937 s` | `183,946 s` | `-5,336%` |
| 23 queries exportadas — WAL | `652.856.482 B` | `437.062.413 B` | `491.783.960 B` | `-24,672%` |

A redução direta do custo da auditoria reapareceu e, no B2, também reduziu o
tempo SQL e o WAL agregados. A variação dos demais statements não foi uniforme:
o select PACS.002 e o update aceito ficaram mais baratos que no baseline,
enquanto inserts de pagamento, outbound notification e delivery index ficaram
mais caros por row.

| resultado end-to-end | baseline | B1 | B2 | B2 vs. baseline |
| --- | ---: | ---: | ---: | ---: |
| TPS médio / mínimo rolling | `1.995,683 / 1.914` | `1.989,017 / 1.903` | `1.992,550 / 1.907` | `-0,157% / -0,366%` |
| latência p50 | `831,572 ms` | `1.068,209 ms` | `817,123 ms` | `-1,738%` |
| latência p95 | `1.488,426 ms` | `1.706,421 ms` | `1.815,940 ms` | `+22,004%` |
| latência p99 | `1.730,257 ms` | `2.050,477 ms` | `2.100,281 ms` | `+21,385%` |
| latência máxima | `2.080,070 ms` | `2.636,731 ms` | `2.612,402 ms` | `+25,592%` |
| outcomes ativos dentro de `1 s` | `71,714%` | `39,558%` | `74,952%` | `+3,238 pp` |
| CPU média PostgreSQL no ativo | `101,358%` | `101,076%` | `102,142%` | `+0,785 pp` |

A repetição não reproduziu a piora do p50 nem da fração dentro de `1 s` do B1,
mas reproduziu a cauda acima do baseline: p95, p99 e máxima foram maiores nos
dois Bs. Portanto, as duas medições sustentam a economia física da remoção dos
índices e mostram que ela não resolve a fonte atual da cauda. Elas não isolam a
causa dessa cauda nem autorizam outra alteração; essa investigação permanece
separada da decisão sobre manter a compactação do índice.

#### Nova A limpa e revisão da interpretação

Uma nova A foi executada para testar se o primeiro baseline representava a
variância natural do sistema. A stack e os volumes foram novamente recriados.
Antes de qualquer funding, instrumentação ou tráfego, a tabela vazia recebeu de
volta `payment_audit_event_pkey` e `idx_payment_audit_event_payment`. O preflight
comprovou zero eventos, quatro índices e uma PK. O bundle está em
`audit-index-baseline-repeat/20260822_155458`.

A2 aceitou todos os `271.495` POSTs e não apresentou outcome ausente,
contraditório nem violação de replay/Pull. Ao final, os quatro índices e a PK
continuavam presentes; a heap tinha `83 MB` e os índices, `101 MB`. Apesar da
correção funcional, A2 teve uma cauda muito pior que A1 e que ambos os Bs.

| resultado end-to-end | A1 | A2 | B1 | B2 |
| --- | ---: | ---: | ---: | ---: |
| TPS médio | `1.995,683` | `1.993,983` | `1.989,017` | `1.992,550` |
| mínimo rolling | `1.914` | `1.918` | `1.903` | `1.907` |
| latência p50 | `831,572 ms` | `1.016,354 ms` | `1.068,209 ms` | `817,123 ms` |
| latência p95 | `1.488,426 ms` | `3.271,407 ms` | `1.706,421 ms` | `1.815,940 ms` |
| latência p99 | `1.730,257 ms` | `3.898,830 ms` | `2.050,477 ms` | `2.100,281 ms` |
| latência máxima | `2.080,070 ms` | `4.582,609 ms` | `2.636,731 ms` | `2.612,402 ms` |
| outcomes ativos dentro de `1 s` | `71,714%` | `48,239%` | `39,558%` | `74,952%` |

A grande distância entre A1 e A2 invalida a interpretação anterior de que a
cauda dos Bs, por estar acima somente de A1, fosse uma regressão atribuível à
V15. Na comparação limpa mais recente, B2 contra A2:

| sinal | A2 | B2 | variação |
| --- | ---: | ---: | ---: |
| latência p50 | `1.016,354 ms` | `817,123 ms` | `-19,603%` |
| latência p95 | `3.271,407 ms` | `1.815,940 ms` | `-44,491%` |
| latência p99 | `3.898,830 ms` | `2.100,281 ms` | `-46,130%` |
| outcomes ativos dentro de `1 s` | `48,239%` | `74,952%` | `+26,712 pp` |
| 23 queries exportadas — SQL | `234,279 s` | `183,946 s` | `-21,484%` |
| 23 queries exportadas — WAL | `626.093.901 B` | `491.783.960 B` | `-21,452%` |
| auditoria — SQL/row | `89,439 us` | `42,548 us` | `-52,428%` |
| auditoria — WAL/row | `436,771 B` | `235,552 B` | `-46,070%` |

O custo físico da auditoria é a evidência estável: os dois As ficaram entre
`68,906` e `89,439 us/row` e aproximadamente `437 B/row`; os dois Bs, entre
`42,548` e `58,190 us/row` e aproximadamente `235 B/row`. A latência
end-to-end varia muito mais e não sustenta uma regressão causada pela remoção
dos índices. A V15 também não qualifica o sistema: todas as quatro runs ficaram
abaixo do piso rolling de `2.000 TPS` e acima do p99 de `1 s`. A decisão sobre
manter a compactação pode usar o ganho físico reproduzido, enquanto a origem da
variância e da cauda permanece como investigação distinta.

### Fronteira conhecida do Pull em memória

O read path do Pull consultava PostgreSQL sempre que o buffer recente não
continha dados depois do cursor. No estado saudável, isso transformava cada
Pull que alcançava a ponta conhecida do fluxo em um `SELECT` vazio. O bundle
`audit-index-compaction-repeat/20260822_141246` caracterizou esse custo com
`113.151` chamadas, somente `5.841` rows retornadas e `19.584,633 ms` de tempo
SQL acumulado.

O buffer passou a manter, por PSP, uma frontier efêmera `confirmedThrough`.
Ela só é estabelecida por uma leitura do PostgreSQL que retorna menos que o
limite solicitado: resposta vazia confirma o próprio cursor e resposta parcial
confirma a última posição retornada. Uma página cheia não prova a ponta. Novas
posições indexadas avançam a frontier somente quando formam uma sequência
contígua; lacunas, posições já expulsas do buffer e restart continuam usando o
fallback SQL. Não foi adicionado cursor persistente, reidratação de payload ou
mudança no protocolo.

O diagnóstico limpo
`pull-known-tail/20260822_203915` preservou profile, recursos, instrumentação e
reset da stack do B2. A consulta de fallback passou a ocorrer uma vez por PSP
para estabelecer a frontier e depois deixou de participar do polling saudável:

| sinal do fallback Pull | B2 | fronteira conhecida | variação |
| --- | ---: | ---: | ---: |
| chamadas | `113.151` | `100` | `-99,912%` |
| rows retornadas | `5.841` | `0` | `-100%` |
| tempo SQL acumulado | `19.584,633 ms` | `2,506 ms` | `-99,987%` |
| tempo médio por chamada | `0,173 ms` | `0,025 ms` | `-85,549%` |

O run processou `272.078/272.078` POSTs e `217.660/217.660` PACS.002, sem
outcome ausente ou contraditório e sem violação de replay ou Pull. A latência
ativa caiu de `817,123 / 1.815,940 / 2.100,281 ms` em p50/p95/p99 no B2 para
`193,071 / 597,650 / 815,602 ms`; o p99 ficou dentro do SLA de `1 s`. Essa
comparação é informativa e não atribui sozinha toda a melhora end-to-end ao
fallback removido, mas o efeito local da intervenção é direto e dominante.

O run permaneceu inválido somente para qualificação do piso sustentado: iniciou
`119.349` pagamentos ativos, média de `1.989,15 TPS` e mínimo rolling de
`1.898 TPS`. Não houve carga fora da janela. Portanto, a intervenção removeu o
polling SQL vazio e preservou correctness, mas ainda não prova a capacidade
contratada de `2.000 TPS` contínuos.

### Fronteira conhecida da posição de delivery

Depois de remover o polling vazio do Pull, o próximo trabalho repetido do
Gateway era a busca da última `delivery_position` de cada PSP em toda transação
de indexação. O bundle `pull-known-tail/20260822_203915` registrou `6.644`
chamadas, `165.374` posições consultadas e `9.991,803 ms` de tempo SQL
acumulado. A posição só pode avançar por um writer lógico por PSP nesta
implantação, portanto essa leitura não precisava ser repetida depois que o
processo conhecia uma posição já commitada.

O repositório de indexação passou a manter por PSP um estado process-local com
lock e última posição commitada. Kafka e reconciler compartilham o mesmo estado.
Os locks locais são adquiridos em ordem determinística; somente PSPs ainda
desconhecidos consultam o PostgreSQL; a posição em memória avança apenas depois
do commit e antes de liberar o lock. Falha ou resultado transacional incerto
invalida os PSPs afetados, fazendo a próxima tentativa recarregar a posição
durável. Advisory lock e constraints do banco permanecem como proteção de
corretude.

Essa otimização assume uma instância do Notification Gateway, que é o escopo do
MVP. Em múltiplas instâncias, o caminho continua protegido pelo banco e consegue
se recuperar de conflito, mas perderia o benefício estável do cache; um contador
durável próprio deverá ser avaliado antes de qualificar essa topologia.

O diagnóstico limpo `position-cache/20260822_210828` preservou profile,
recursos, instrumentação e reset da stack do baseline anterior:

| sinal da última posição | baseline | cache por PSP | variação |
| --- | ---: | ---: | ---: |
| chamadas | `6.644` | `15` | `-99,774%` |
| PSPs consultados | `165.374` | `90` | `-99,946%` |
| tempo SQL acumulado | `9.991,803 ms` | `1,034 ms` | `-99,990%` |
| tempo médio por chamada | `1,504 ms` | `0,069 ms` | `-95,412%` |

As 15 leituras ocorreram enquanto os PSPs eram descobertos nos primeiros lotes;
depois disso a consulta saiu do steady state. A quantidade não é um novo
contrato: ela depende do agrupamento dos primeiros lotes, enquanto o resultado
relevante é não reler posições já conhecidas.

O active iniciou `119.792` pagamentos, com média de `1.996,533 TPS` e mínimo
rolling de `1.938 TPS`. A latência p50/p95/p99 caiu de
`193,071 / 597,650 / 815,602 ms` para
`141,349 / 386,057 / 526,965 ms`, e o total SQL exportado caiu de
`95,208 s` para `93,337 s`. A comparação end-to-end é informativa; o efeito
direto atribuído à mudança é a eliminação das leituras repetidas de posição.

O run não qualificou: duas requisições do começo do warmup terminaram em
timeout HTTP (`status 0`), uma por cenário, e o mínimo rolling permaneceu abaixo
de `2.000 TPS`. Não houve outcome ausente ou contraditório, violação de replay ou
Pull, carga fora da janela ou estouro do SLA ativo. O código permanece no
worktree para decisão explícita do projeto; o benchmark não acionou reversão
automática.

### Ajuste final do batching PACS.002

Depois de estabilizar o PACS.008, o consumer PACS.002 ainda formava lotes
pequenos e executava muitas transações curtas. A investigação preservou um
consumer, o workload, os recursos e o timeout máximo de `125 ms`, alterando uma
variável por vez. Os primeiros candidatos foram:

1. `pacs002-kafka-batch-200/20260823_042452`: `500 / 128 KiB / 125 ms`,
   buscando aproximadamente `200` status por timeout no ativo;
2. `pacs002-fetch-min-16k/20260823_060042`: manteve o máximo em `500` e
   reduziu somente `fetch.min.bytes` para `16 KiB`;
3. `pacs002-fetch-16k-max-220/20260823_060838`: limitou o poll a `220` para
   testar se evitar lotes maiores protegeria a cauda;
4. `pacs002-batch-formation-125ms/20260823_072200`: adicionou ao JFR a
   cardinalidade real entregue aos listeners;
5. `pacs002-callback-idle-125ms/20260823_075131`: estendeu o mesmo evento JFR
   com a duração integral do callback, permitindo separar processamento de
   espera entre polls.

Reduzir o mínimo de `128 KiB` para `16 KiB` melhorou a latência observada, mas
o limite de `220` criou uma nova fronteira artificial. Entre o primeiro
candidato e o limite de `220`, as chamadas do lock/read PACS.002 subiram de
`1.479` para `1.864`, e as chamadas do update aceito, de `1.302` para `1.709`.
A instrumentação confirmou que o p95, p99 e máximo do batch ficavam exatamente
em `220`, caracterizando fragmentação pelo limite, não falta de registros no
consumer.

O A/B decisivo removeu somente essa fragmentação. O controle
`pacs002-callback-idle-125ms/20260823_075131` usou `220 / 16 KiB / 125 ms`; o
candidato `pacs002-fetch-16k-max-500-callback-idle/20260823_081953` usou
`500 / 16 KiB / 125 ms`. Profiles e planos de execução eram byte-idênticos.

| sinal no ativo | máximo `220` | máximo `500` | leitura |
| --- | ---: | ---: | --- |
| callbacks PACS.002 | `491` | `391` | `-20,37%` |
| registros observados | `63.380` | `63.657` | população equivalente |
| batch médio | `129,084` | `162,806` | `+26,13%` |
| batch p50 / p95 / p99 / máximo | `164 / 220 / 220 / 220` | `177 / 277 / 319 / 339` | o poll deixou de cortar lotes disponíveis |
| processamento total dos callbacks | `7.171,355 ms` | `5.442,326 ms` | `-24,11%` |
| processamento p95 / p99 | `30,838 / 134,368 ms` | `25,825 / 62,490 ms` | menor cauda de processamento |
| chamadas do lock/read PACS.002 | `1.888` | `1.571` | `-16,79%` |
| chamadas do update aceito | `1.729` | `1.406` | `-18,68%` |
| latência E2E p50 | `176,629 ms` | `179,148 ms` | praticamente estável |
| latência E2E p95 / p99 | `377,347 / 500,166 ms` | `320,677 / 489,036 ms` | cauda menor |

Ambos os runs aceitaram todos os POSTs e concluíram todos os outcomes, sem
ausência, contradição ou violação de replay/Pull. O `max.poll.records=500` é uma
capacidade máxima, não um batch-alvo: o maior lote observado foi `339`.

Foi executado ainda o candidato descartável
`pacs002-fetch-20k-max-500-callback-idle/20260823_084032`, alterando somente
`fetch.min.bytes` de `16 KiB` para `20 KiB`. Ele não produziu o aumento de lote
esperado: o batch médio passou apenas de `162,806` para `164,354`, e os
callbacks, de `391` para `387`. Em contrapartida:

| sinal | `16 KiB` | `20 KiB` |
| --- | ---: | ---: |
| processamento total dos callbacks | `5.442,326 ms` | `8.686,456 ms` |
| processamento p95 / p99 | `25,825 / 62,490 ms` | `74,555 / 127,529 ms` |
| latência E2E p50 | `179,148 ms` | `207,184 ms` |
| latência E2E p95 / p99 | `320,677 / 489,036 ms` | `482,211 / 668,482 ms` |
| CPU média PostgreSQL no ativo | `61,96%` | `71,17%` |
| fallback SQL do Pull | `100` chamadas | `1.631` chamadas |
| mínimo rolling | `1.937 TPS` | `1.918 TPS` |

O ganho de quatro callbacks não compensou a piora sistêmica. O experimento de
`20 KiB` foi descartado e a configuração voltou ao estado commitado. A decisão
vigente para o ambiente de performance é:

| consumer | concorrência | `max.poll.records` | `fetch.min.bytes` | `fetch.max.wait.ms` |
| --- | ---: | ---: | ---: | ---: |
| PACS.008 | `1` | `500` | `128 KiB` | `100 ms` |
| PACS.002 | `1` | `500` | `16 KiB` | `125 ms` |

O evento JFR de batch permanece como instrumentação permanente e registra
tópico, quantidade de records e duração do callback. Nenhum desses diagnósticos
qualifica sozinho a meta contratada: todos ficaram abaixo do piso rolling
contínuo de `2.000 TPS`. Eles caracterizam somente a decisão de batching. A
corretude permaneceu íntegra e o controle final de `16 KiB` manteve toda a
latência ativa abaixo do SLA de `1 s`.

### Pré-seleção de replays antes do insert PACS.008

O caminho de admissão executava `INSERT ... ON CONFLICT DO NOTHING RETURNING`
para todo candidato e consultava depois somente os conflitos. Como replays são
uma minoria conhecida do workload, foi testado o caminho inverso: consultar os
IDs do lote primeiro, classificar os pagamentos já persistidos em Java e
executar um `INSERT` estrito somente para os ausentes. O insert verifica que a
quantidade afetada corresponde a todos os candidatos novos; uma violação da PK
continua abortando a transação.

O escopo de performance vigente usa uma instância do SPI e um listener
PACS.008. Os defaults do listener foram alinhados para concorrência `1`. A
coordenação de inserts concorrentes do mesmo `payment_id` fica fora desta
intervenção e deverá ser desenhada junto da futura qualificação multi-instância.

No microbenchmark isolado de `100.000` entradas em batches de `200`, a
pré-seleção reduziu o tempo de parede em `47,5%` sem replay e em `33,6%` com
`5%` de replay; o tempo SQL combinado caiu respectivamente `68,5%` e `65,9%`,
e o WAL caiu aproximadamente `14%` nos dois casos.

O A/B end-to-end comparou os bundles
`pacs008-fetch-min-56k/20260823_142558` e
`payment-preselect-strict-insert/20260823_153931`, ambos com stack e volumes
novos e com o mesmo profile `mixed-outcomes-2k-diagnostic`:

| sinal | `ON CONFLICT` | pré-seleção + insert estrito | variação |
| --- | ---: | ---: | ---: |
| pagamentos ativos / TPS médio | `119.871 / 1.997,850` | `119.883 / 1.998,050` | população equivalente |
| mínimo rolling | `1.956 TPS` | `1.943 TPS` | `-0,665%` |
| latência p50 | `162,884 ms` | `160,045 ms` | `-1,743%` |
| latência p95 | `267,375 ms` | `271,273 ms` | `+1,458%` |
| latência p99 | `386,178 ms` | `488,553 ms` | `+26,510%` |
| latência máxima | `585,028 ms` | `806,561 ms` | `+37,867%` |
| insert + classificação existente — SQL | `11.090,785 ms` | `10.563,446 ms` | `-4,755%` |
| insert + classificação existente — WAL | `94.234.352 B` | `80.660.167 B` | `-14,405%` |
| statements exportados — SQL total | `42.494,081 ms` | `44.879,362 ms` | `+5,613%` |

O candidato aceitou todos os `279.769` POSTs, concluiu todos os outcomes e
replays e não apresentou ausência, contradição ou violação de Pull. A economia
física local de WAL reapareceu, mas a economia SQL do caminho de admissão foi
pequena no sistema completo: o custo removido do insert foi em grande parte
substituído pela consulta de todos os candidatos. A cauda e o SQL global
pioraram nessa execução, enquanto o p50 permaneceu praticamente estável.

Os dois runs ficaram inválidos somente porque o mínimo rolling permaneceu
abaixo de `2.000 TPS`; ambos ficaram inteiramente dentro do SLA de `1 s`. A
medição caracteriza o efeito local e a ausência de ganho end-to-end inequívoco,
sem atribuir a variação da cauda exclusivamente a esta mudança.
