# Estabilização da stack em 2.000 pagamentos por segundo

## Resultado

A stack local foi qualificada duas vezes para sustentar pelo menos `2.000`
pagamentos originais por segundo durante toda a janela ativa de `15 minutos`.
O profile oferece `2.100 TPS`; o piso é avaliado em toda janela contínua de um
segundo, sem usar média ou picos posteriores para compensar uma queda.

As duas execuções partiram de containers e volumes novos, usaram o mesmo
código, profile, recursos e instrumentação e terminaram sem perda, contradição
de outcome ou violação de replay.

| sinal | primeira execução | repetição |
| --- | ---: | ---: |
| originais planejados / executados no active | `1.890.000 / 1.890.000` | `1.890.000 / 1.890.000` |
| TPS médio ativo | `2.100` | `2.100` |
| mínimo rolling de 1 segundo | `2.079` | `2.079` |
| latência p50 / p95 | `144,418 / 236,033 ms` | `146,514 / 236,939 ms` |
| latência p99 / máxima | `268,134 / 928,778 ms` | `259,956 / 606,208 ms` |
| PACS.008 replayados / aceitos | `100.500 / 100.500` | `100.500 / 100.500` |
| PACS.002 replayados / aceitos | `80.392 / 80.392` | `80.393 / 80.393` |
| violações funcionais / replay | `0 / 0` | `0 / 0` |

O threshold interno é p99 abaixo de `1 segundo`. O contrato externo permanece
p99 abaixo de `4,6 segundos`. A qualificação, portanto, preserva margem interna
sem redefinir o contrato.

Este documento é a síntese. O histórico completo de hipóteses, A/Bs e
diagnósticos permanece no
[`caderno de estabilização`](../board/Atividades/concluidas/estabilizar-teste-carga-budget-cpu.md).
Os resultados intermediários que alteraram decisões foram curados no
[apêndice de achados experimentais](experimental-findings.md), com evidência,
decisão e limitações separadas.

## Workload qualificado

O profile oficial é `mixed-outcomes-2k-15m`:

```text
warmup bootstrap :  500 TPS / 60 s / timeout causal de 30 s
warmup steady    : 1500 TPS / 60 s / timeout causal de 5 s
gate do warmup   : até 120 s para concluir obrigações observáveis
active           : 2100 TPS oferecidos / piso rolling de 2000 TPS / 15 min
drain            : 30 s fixos
replay PACS.008  : 5% / atraso de 10 s
replay PACS.002  : 5% / atraso de 10 s
```

O mix funcional é:

| cenário | participação | resultado esperado |
| --- | ---: | --- |
| happy-path | `80%` | HTTP 2xx e PACS.002 `ACSC` ao pagador |
| insufficient-funds | `20%` | HTTP 2xx e PACS.002 `RJCT/AM04` ao pagador |

Os replays são carga adicional. Eles não reduzem nem substituem os pagamentos
originais contabilizados no piso. A distribuição mantém `80%` do tráfego nos
pares quentes de cada cenário.

O perfil e o plano normalizado usados na qualificação estão preservados em
[`evidence/2026-08-27`](evidence/2026-08-27/manifest.md).

## Fronteira de medição

A latência começa quando o PSP simulado inicia o request original e termina
quando ele observa um outcome final compatível para o pagador. Entregas físicas
repetidas são permitidas pelo contrato at-least-once; ausência, status
contraditório ou motivo incompatível continuam sendo falhas de corretude.

O gerador usa buckets absolutos de `10 ms`, sem carry-over. Um PACS.008 original
só é admitido quando payload e capacidade HTTP/2 estão prontos antes do deadline
do bucket. Trabalho atrasado não é despejado numa janela posterior.

O throughput é reconstruído depois do experimento a partir dos timestamps de
início HTTP. O relatório ordena esses instantes e mede o menor número de
originais em qualquer janela contínua de um segundo integralmente contida no
active. Média e quantidade total são diagnósticos, não aprovação.

O gate entre warmup e active afirma somente o que o load-tool observa: o active
não começa enquanto ainda existe obrigação de warmup criada e rastreada pelo
próprio gerador. Lag Kafka zero não é usado como prova de quiescência interna.

## Ambiente e recursos

O load generator executa no host e não entra no consumo atribuído à stack. Os
serviços medidos são PostgreSQL, Kafka, ingresso HTTP (`kafka-producer`), SPI e
Notification Gateway.

Os limites efetivos do Compose nos runs finais eram:

| componente | limite de CPU | limite de memória |
| --- | ---: | ---: |
| PostgreSQL | `1,00` | `512 MiB` |
| Kafka | `1,00` | `2048 MiB` |
| ingresso HTTP | `1,00` | `384 MiB` |
| SPI | `1,00` | `768 MiB` |
| Notification Gateway | `1,00` | `512 MiB` |
| total dos limites individuais | `5,00` | `4224 MiB` |

O alvo de `3 vCPUs / 3 GiB` foi avaliado pelo consumo observado durante o
active, não por um cgroup agregado. Essa distinção é importante: os resultados
provam o consumo medido, mas não provam comportamento sob um teto físico único
de três CPUs.

| sinal observado no active | primeira execução | repetição |
| --- | ---: | ---: |
| CPU média agregada | `1,178 vCPU` | `1,161 vCPU` |
| maior amostra agregada de CPU | `2,174 vCPU` | `2,222 vCPU` |
| memória média agregada | `1940,6 MiB` | `1900,6 MiB` |
| maior amostra agregada de memória | `2134,0 MiB` | `2013,8 MiB` |

CPU média por componente:

| componente | primeira execução | repetição |
| --- | ---: | ---: |
| PostgreSQL | `41,09%` | `39,78%` |
| ingresso HTTP | `30,23%` | `30,20%` |
| Notification Gateway | `23,95%` | `23,63%` |
| Kafka | `12,26%` | `12,22%` |
| SPI | `10,26%` | `10,27%` |

As amostras vieram de `docker stats`: foram `703` e `699` amostras completas
durante os respectivos actives. Elas não formam uma garantia contínua entre
amostras. A execução diagnóstica final do load-tool registrou aproximadamente
`59,6 MiB` de RSS máximo, fora do budget da stack.

## Método de estabilização

O trabalho seguiu quatro regras:

1. preservar workload e recursos durante cada A/B;
2. mudar uma variável relevante por vez;
3. medir trabalho útil e custo por pagamento, não apenas CPU ocupada;
4. executar o profile longo somente depois que o diagnóstico curto justificasse
   o custo.

PostgreSQL foi investigado por `pg_stat_statements`, activity sampling,
I/O/WAL, lock waits, `EXPLAIN ANALYZE` e, numa execução descartável,
`log_executor_stats`. JFR separou compilação, TLS, threads e espera JDBC nos
processos Java. Os bundles preservaram requests, outcomes, replays e recursos
para permitir reconstrução posterior.

Uma conclusão recorrente foi que `total_exec_time` de uma query não equivale a
CPU intrínseca. Sob um PostgreSQL saturado, uma operação pode liderar wall-time
por esperar capacidade. A instrumentação nativa atribuiu `74,38%` da CPU de
executor medida ao antigo ciclo de outbox/delivery e somente `6,63%` ao conjunto
de lock, transição e saldo diretamente associado ao PACS.002. Isso mudou o alvo
de otimização do settlement isolado para o lifecycle de notificações.

## Lições dos experimentos intermediários

Os microbenchmarks serviram para demonstrar mecanismos, não para declarar ganho
de capacidade. A simplificação do update PACS.002 reduziu seu tempo SQL
acumulado em `69,69%` e sua pior execução em `98,32%`, mas o A/B end-to-end
concluiu `11,04%` menos outcomes porque o PostgreSQL permaneceu saturado e o
limite migrou para etapas anteriores e para o ciclo compartilhado de escrita.
A regra resultante foi medir novamente o caminho causal completo depois de toda
otimização local relevante.

O `fillfactor=50` da tabela de pagamentos também apresentou um trade-off real:
os updates HOT passaram de `22,86%` para `100%`, e o custo e WAL das transições
caíram materialmente. Em contrapartida, insert e lock/leitura ficaram mais
caros, heap mais índices cresceram `46,98%` e os outcomes concluídos ficaram
praticamente iguais (`-0,02%`). O layout foi mantido pela eficiência física das
transições, não como explicação para a capacidade end-to-end final.

Nem todo ganho de microbenchmark foi promovido. Pré-selecionar IDs antes da
admissão PACS.008 reduziu o SQL isolado em mais de `65%`, mas no sistema completo
a consulta adicional absorveu quase todo o benefício: o SQL global subiu
`5,61%` e o p99 subiu `26,51%`. O caminho vigente preserva `INSERT ... ON
CONFLICT DO NOTHING` e consulta somente os conflitos. Esse resultado reforçou a
preferência por reduzir o trabalho dominante, em vez de otimizar uma operação
isolada com uma nova leitura no hot path.

O tuning dos consumers foi guiado pelos lotes realmente entregues, não apenas
pelos limites configurados. No PACS.002, elevar `max.poll.records` de `220` para
`500` não criou lotes de 500: o máximo observado foi `339`, enquanto o batch
médio subiu de `129,084` para `162,806`. Isso reduziu callbacks em `20,37%` e o
tempo de processamento dos callbacks em `24,11%`. Já elevar
`fetch.min.bytes` de `16` para `20 KiB` quase não alterou o batch médio
(`162,806 → 164,354`), mas elevou o p99 end-to-end de `489,036` para
`668,482 ms`. A decisão durável é tratar esses parâmetros como limites de
formação e sempre observar a distribuição real e a cauda resultante.

No PACS.008, a comparação diagnóstica que levou `fetch.min.bytes` de `128` para
`56 KiB` reforçou a mesma regra. Considerando todos os callbacks registrados no
JFR, a mediana do lote permaneceu em `165` e a média variou pouco
(`155,291 → 152,010`), mas p99 e máximo do lote caíram de `281/493` para
`235/350`. O p99 do callback caiu de `105,511` para `72,191 ms`, e o p99
end-to-end, de `566,941` para `386,178 ms`. Os dois runs preservaram os outcomes
e ficaram abaixo do piso rolling (`1.967` e `1.956 TPS`); portanto sustentam
`56 KiB` como redução conservadora de espera e cauda, não como prova isolada de
maior capacidade.

A evolução interna do gerador Rust também descartou soluções aparentemente
óbvias. O protótipo de `1 ms` perdeu `30.877` de `246.000` slots; buckets de
`10 ms` reduziram a perda para `1.170`, e um coordenador de buckets para `104`.
Depois disso, aumentar o canal repetiu os mesmos `21` misses, pinning de CPU
produziu `29`, e elevar o spin para `1 ms` ainda deixou `12` misses ao custo de
`16,012 s` de spin acumulado. Um diagnóstico separou apenas um atraso no retorno
do sleep de `26` perdas antes do commit: o problema restante não era acordar a
thread, mas preparar e admitir o trabalho a tempo.

O planner compartilhado foi a mudança decisiva. Ele executou `246.000/246.000`,
zerou misses, reduziu p99 do pacer para `0,244 ms` e p99 do início HTTP para
`0,228 ms`; o user CPU caiu de aproximadamente `211–220 s` nas variantes
anteriores para `37,570 s`. O cutover completo preservou zero misses e reduziu
o RSS máximo para cerca de `59,6 MiB`. A lição durável é resolver ownership e
preparação antes de ajustar timer, fila ou afinidade do host.

Profiling posterior também delimitou onde parar. Heaptrack contou `47,59`
milhões de alocações, principalmente em formatação do recorder e preparação
HTTP/2, mas perturbou o próprio run: RSS chegou a `491 MiB` e p99 a
`718,061 ms`. O perfil de CPU não apresentou um símbolo de aplicação dominante.
Como o diagnóstico normal já qualificava com baixo RSS e lateness, buffer pool,
allocator customizado e encoder JSON manual não foram promovidos sem evidência
de benefício sistêmico.

## Evolução dos gargalos e decisões

| evidência | decisão mantida | efeito observado |
| --- | --- | --- |
| baseline com média `2.113,898 TPS`, mínimo `0` e pico `10.563` | substituir média por rolling contínuo e eliminar carry-over | o gerador deixou de mascarar buracos com picos posteriores |
| `6.019` handshakes TLS no active, ingresso bloqueado e `5.717` timeouts | clientes persistentes, HTTP/2 obrigatório e prewarm autenticado por PSP | handshakes ativos e timeouts caíram a zero; o gargalo migrou ao SPI/PostgreSQL |
| buckets fragmentavam liquidez e settlement tocava pagador e recebedor | saldo único por participante e reserva no PACS.008 | cada fase financeira passou a alterar somente um participante; replays continuam idempotentes |
| excesso de transações concorrentes no único core do PostgreSQL | formar batches maiores e ajustar concorrência dos listeners por fluxo | no primeiro A/B PACS.002, rows/chamada subiram `49,118 → 319,714` e SQL caiu `469,732 s → 5,901 s` |
| CTE de admissão PACS.008 fazia classificação no banco | classificar o batch em Java e consultar conflitos somente quando necessário | p95/p99 HTTP caíram `264,820/698,384 → 16,890/38,731 ms` no diagnóstico correspondente |
| uma mensagem persistida por item de notificação | agrupar até 15 itens por destinatário | rows de outbox caíram `78,04%`, SQL do insert `90,78%`, WAL `77,75%` e outcomes ausentes chegaram a zero no A/B |
| reliable push exigia claim, lease, ACK e persistência de progresso | Pull unary com cursor durável no PSP | redelivery passou a ser reapresentação de cursor antigo; ACK individual saiu do hot path |
| arquitetura híbrida ainda mantinha índice, reconciliação e duas ordens duráveis | Kafka como log durável por sete dias; PostgreSQL conserva apenas a outbox transacional | contra o híbrido longo, p99 caiu `69,43%`, SQL exportado `61,49%` e CPU média do PostgreSQL `22,01 pp` |
| stack fria falhava antes da fase steady apesar de estabilizar depois | warmup bootstrap e steady separados, seguidos por gate observável | `7.688` timeouts frios foram eliminados sem relaxar o SLA do active |
| Go acumulou responsabilidades e seu overhead interferia no pacing | load-tool greenfield em Rust, gerador separado fisicamente do report | diagnóstico final: `126.000/126.000` ativos, rolling `2.079`, p99 `253,867 ms` e pacer p99 `0,322 ms` |

As decisões financeiras e de entrega estão detalhadas em:

- [Saldo por participante com reserva implícita](../architecture/reservation-based-participant-balance.md);
- [Entrega durável de notificações pelo Kafka](../architecture/kafka-durable-notification-delivery.md).

## Arquitetura resultante

```text
PSP / load-tool Rust
        │ HTTP/2 + mTLS
        ▼
ingresso HTTP
        │ Kafka: PACS.008 por pagador
        ▼
SPI
  ├─ reserva saldo do pagador
  ├─ persiste pagamento + auditoria + notification_outbox atomicamente
  └─ processa PACS.002 em batches
        │
        ▼
Kafka psp-notifications-v1
  ├─ log durável por 7 dias
  ├─ 8 partições por recipient_ispb
  └─ duplicata física permitida com communication_id estável
        │
        ▼
Notification Gateway
  ├─ buffer recente em memória
  ├─ fallback histórico direto no Kafka
  └─ Pull(cursor) com até 15 envelopes
        │
        ▼
PSP persiste lote e somente então avança o cursor
```

O PostgreSQL é autoridade da transação financeira e da criação atômica da
obrigação. Kafka é autoridade do histórico operacional de delivery dentro da
retenção. O PSP é autoridade sobre seu progresso durável de consumo. O Gateway
não mantém ACK individual nem estado persistido de progresso.

## Alternativas descartadas ou superadas

- O primeiro scheduler compensava atraso com catch-up. Foi removido porque
  alterava a workload medida.
- Lag Kafka zero foi descartado como prova de conclusão end-to-end: offsets
  consumidos não provam outbox, persistência nem delivery concluídos.
- A arquitetura híbrida `outbound_notification + delivery_index + reconciler`
  reduziu custos locais, mas preservava duplicação de estado e pressão no banco
  financeiro. Foi substituída pelo log Kafka durável.
- O protótipo Rust inicial com buckets de `1 ms` perdeu `30.877` slots e não foi
  promovido. Buckets absolutos de `10 ms` preservaram a forma temporal com menor
  sensibilidade ao scheduler do host.
- Afinidade de CPU e políticas especiais de scheduling não foram mantidas como
  requisito. Uma máquina separada e prioridade apropriada continuam boas
  práticas para um benchmark externo, não dependências do MVP local.
- `log_executor_stats` foi usado somente para atribuição diagnóstica e desligado
  depois da medição por perturbar a workload.
- Reutilizar a stack entre runs foi abandonado na qualificação: trabalho antigo
  podia reaparecer no Pull e contaminar a execução seguinte.

## Reprodução

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

Para repetir uma execução qualificadora, executar novamente o preparador antes
do runner. O preparador recria volumes, sobe a stack, aguarda readiness,
provisiona fundos e certificados e não gera tráfego.

O relatório não encerra o processo com erro apenas porque uma meta de
performance não foi atingida. A aprovação é feita pelos fatos persistidos:
originais planejados/executados, `minimum_rolling_tps`, latências e violações de
corretude.

## Evidência preservada

Os bundles qualificadores permanecem locais e ocupam aproximadamente `1,1 GiB`
cada:

```text
load-test/results/rust-qualification-15m-clean/20260827_030742
load-test/results/rust-qualification-15m-repeat-clean/20260827_033639
```

O repositório preserva somente profile, plano normalizado, relatórios e
checksums dos artefatos grandes. CSVs, JFRs, logs e certificados não devem ser
adicionados ao Git comum. O manifesto registra a limitação de que a revisão Git
foi reconstruída pelo histórico local, pois o bundle final não a persistiu.

## Limite exploratório acima da meta

Diagnósticos curtos a `4.000 TPS` foram executados somente para localizar o
próximo limite, não para ampliar a capacidade declarada. Eles preservaram todos
os outcomes funcionais e iniciaram quase toda a carga planejada, mas o mínimo
rolling ficou entre `3.920` e `3.960 TPS` e o p99 end-to-end entre `1,36` e
`2,45 segundos`. Portanto nenhum deles comprovou o piso de `4.000 TPS` nem o
threshold interno de `1 segundo`.

A primeira fila dominante apareceu no consumer PACS.008. Elevar
`max.poll.records` de `500` para `1.000` não resolveu o limite: cerca de `10%`
dos callbacks já excediam `500` registros, o p99 do callback aumentou de
aproximadamente `153` para `250 ms` e mais trabalho terminou depois da janela
ativa. O limite homologado permaneceu `500`; concorrência adicional do listener
e múltiplas instâncias pertencem à homologação futura, não à qualificação da
stack única.

## Limitações e trabalhos futuros

- Os runs foram locais e o gerador compartilhou o host com a stack, embora seu
  consumo fique fora do budget medido.
- O budget de `3 vCPUs / 3 GiB` foi observado por amostragem, não imposto por um
  limite agregado.
- O ambiente possui um broker Kafka e replication factor 1; comprova protocolo
  e performance local, não alta disponibilidade.
- A retenção Kafka é de sete dias. Indisponibilidade maior pertence a disaster
  recovery.
- Não houve homologação Kubernetes nem multi-instância. Esses trabalhos estão
  separados no backlog.
- Os bundles brutos ainda precisam de arquivo externo durável antes da limpeza
  dos resultados locais.

## Conclusão

O ganho final não veio de uma query isolada. Ele veio da remoção progressiva de
trabalho acidental: conexões TLS descartáveis, fragmentação de saldo, excesso
de transações pequenas, mensagens de saída unitárias, ACKs persistidos,
reconciliação de duas fontes duráveis e overhead do próprio gerador.

Com essas fronteiras simplificadas, duas execuções independentes sustentaram
`2.100 TPS` oferecidos, nunca ficaram abaixo de `2.079 TPS` em rolling windows,
mantiveram p99 abaixo de `269 ms` e preservaram todos os outcomes e replays. A
stack única está qualificada para o objetivo local definido; expansão de
infraestrutura e homologação multi-instância são trabalhos separados.
