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
- Se a evidência for inconclusiva, adicionar apenas a instrumentação necessária
  e medir novamente antes de alterar a arquitetura.

## Workloads

- Enquanto não houver fronteira confiável de quiescência end-to-end, preparação
  por execução medida: `cd load-test &&
  ./prepare-performance-environment.sh`. O comando recria volumes, sobe a stack,
  espera readiness e só libera o ambiente após um `mixed-outcomes-smoke`
  funcionalmente completo.
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
- [ ] Executar `prepare-performance-environment.sh` antes do baseline; não
  iniciar o run longo se readiness, smoke ou quiescência falhar.
- [ ] Confirmar que o preparador qualificou os 1.250 pagamentos e os outcomes
  externos do `mixed-outcomes-smoke` antes do run longo.
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

Portanto, o resultado correto é **batching promissor, mas inconclusivo até a
correção da fronteira temporal**. O run B preservou ACSC e RJCT/AM04, melhorou
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

**Decisão: KEEP.** A consulta e os índices simplificados reduzem trabalho por
notificação, aumentam outcomes e melhoram o ingresso sem regressão funcional.
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

**Decisão: KEEP.** Um consumer forma lotes maiores e reduz o custo da
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

**Decisão: KEEP.** O statement estável remove a construção e o parsing
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

**Decisão: KEEP.** A primeira entrega não depende mais de polling/claim, o custo
esperado desapareceu e houve ganho end-to-end sem regressão semântica. O próximo
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
