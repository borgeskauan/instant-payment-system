# Reavaliar custos do load-tool após desacoplar o relatório

- [ ] Reavaliar os custos internos do load-tool depois que a geração deixar de
  gravar diretamente os artefatos consumidos pelo relatório

## Contexto

O load-tool compartilha a máquina com a stack medida e precisa ser o mais leve
possível. Nos runs longos recentes, o mínimo rolling caiu em instantes muito
parecidos da janela ativa, embora a correção funcional e os SLAs de latência
tenham sido preservados:

| Run | mínimo rolling | máximo rolling | p99 HTTP |
| --- | ---: | ---: | ---: |
| `loadtool-lightweight-15m/20260824_152210` | `1.877 TPS` | `2.121 TPS` | `363,725 ms` |
| `loadtool-lightweight-15m-rerun/20260824_154334` | `1.844 TPS` | `2.121 TPS` | `419,490 ms` |

Os vales ocorreram aproximadamente em `active + 617 s` e `active + 616 s`.
Nesses intervalos também houve pressão do PostgreSQL e atividade relevante de
I/O/autovacuum. A coincidência sustenta a hipótese de interferência por recursos
compartilhados, mas não prova isoladamente se a pausa nasceu no load-tool, no
host ou na stack.

O run mais recente produziu aproximadamente `10,8 milhões` de linhas e `978 MiB`
nos quatro CSVs de eventos:

| Artefato | Linhas | Tamanho aproximado |
| --- | ---: | ---: |
| `notifications.csv` | `6.961.386` | `546 MiB` |
| `pacs002-starts.csv` | `1.637.974` | `157 MiB` |
| `pacs008-starts.csv` | `2.047.467` | `256 MiB` |
| `replays.csv` | `184.267` | `19 MiB` |

Hoje os workers serializam esses eventos durante a execução medida. No caso de
`pacs008-starts.csv`, o worker conclui o HTTP e entra em uma seção protegida por
mutex global para montar e escrever a linha. O `bufio.Writer` reduz syscalls,
mas seus flushes e a codificação CSV ainda competem por CPU, memória e disco na
mesma máquina do sistema testado.

O primeiro trabalho deve desacoplar **geração** de **registro dos artefatos que
alimentam o relatório**. O relatório propriamente dito já roda depois da carga;
portanto o risco para a janela ativa está principalmente na produção dos CSVs,
não na leitura posterior deles.

## Achados para revisitar depois do desacoplamento

### 1. Serialização e persistência dos CSVs

É o custo acidental com maior ROI plausível. Desacoplar os writers remove o
bloqueio direto dos workers, mas a codificação de cada linha com
`encoding/csv`, `strconv` e `[]string` continuará consumindo CPU e gerando
alocações no mesmo processo.

Depois da primeira mudança, medir antes de decidir entre:

- manter a implementação caso o rolling estabilize;
- reduzir alocações na codificação preservando exatamente os formatos atuais;
- mudar formatos ou reduzir evidências somente com uma decisão separada, pois
  os artefatos por pagamento continuam necessários para auditoria e gráficos.

### 2. Pull de notificações e decodificação JSON

O run observado realizou `1.032.172` Pulls. O batch efetivo teve média `1,188`,
p50 `1`, p95 `2` e máximo `8`, resultando em aproximadamente `1,2 milhão` de
decodificações completas de envelopes JSON.

Esse custo é alto, porém representa comportamento essencial do PSP simulado.
Não aumentar artificialmente batching nem deixar de validar o payload apenas
para favorecer o gerador. Se ele continuar relevante após o desacoplamento,
primeiro obter perfil de CPU/alocações e então avaliar uma implementação de
parsing equivalente.

### 3. Memória do relatório no pós-processamento

O relatório carrega os quatro CSVs completos e depois constrói mapas e slices de
duração proporcionais ao tamanho do run. É um custo `O(n)` que pode produzir
pico de memória de vários GiB, mas acontece após a janela medida e não explica
diretamente o vale de rolling.

Uma leitura em streaming ou por fases pode reduzir a pegada no futuro. Ela só
deve ser priorizada depois de estabilizar a geração, salvo se o relatório deixar
de concluir por memória.

### 4. Mutex global do estado de pagamentos

`paymentStatesMu` é acessado na criação de pagamentos, na deduplicação e fila de
PACS.002 e na conclusão do outcome do pagador. As seções críticas são pequenas
e o mapa já é limitado pelo lifecycle, mas são milhões de acessos concorrentes
entre workers HTTP e Pulls.

Não fazer sharding preventivo. Investigar apenas se um perfil posterior ao
desacoplamento mostrar contenção material nesse mutex.

### 5. Custos menores já identificados

- o scheduler de replay usa heap e mutex globais e cria um timer por replay;
- o simulador mantém aproximadamente 1.200 goroutines para I/O concorrente;
- métricas de Pull usam atomics no hot path;
- consultas pequenas de expectativas e códigos de motivo ainda são lineares.

Esses pontos têm ROI esperado menor. Só devem ser atacados quando um perfil os
mostrar acima dos custos de CSV, JSON ou sincronização do estado de pagamentos.

## Trabalho já feito que não deve ser reaberto sem evidência

- geração original em buckets de `10 ms`, reduzindo o scheduler para cerca de
  `100` wakeups/s e preservando no-carry-over;
- geração dos payloads PACS com uma única alocação;
- conexões HTTP/2 pré-aquecidas e reutilizadas;
- remoção do trace HTTP por request;
- tentativa de pré-calcular URLs/identificadores, descartada por não produzir
  ganho end-to-end mensurável.

## Ordem de retomada

1. concluir o desacoplamento entre workers de geração e writers dos CSVs;
2. validar em run curto a equivalência dos artefatos, memória limitada e falha
   explícita quando a fila de escrita não acompanhar a carga;
3. executar um run longo comparável e medir rolling, CPU do load-tool, CPU e I/O
   do host, GC, filas dos writers e conclusão do relatório;
4. se o rolling estabilizar, encerrar esta investigação sem novas otimizações;
5. se ainda houver interferência, coletar um perfil descartável de CPU e
   alocações durante o trecho degradado;
6. escolher apenas o custo dominante comprovado: codificação CSV, parsing JSON,
   contenção em `paymentStates` ou outro ponto revelado pelo perfil;
7. tratar a leitura streaming do relatório separadamente, pois ela otimiza o
   pós-processamento e não o hot path medido.

## Critérios de conclusão

- a geração não bloqueia silenciosamente em I/O de artefatos;
- saturação da fila de escrita invalida e encerra o run de forma explícita, sem
  perda silenciosa de evidência;
- todos os CSVs continuam completos e semanticamente equivalentes;
- o custo restante do load-tool é medido depois do desacoplamento, sem atribuir
  antecipadamente o vale de rolling a um único componente;
- nenhuma otimização adicional é mantida sem ganho observável e sem preservar o
  comportamento do PSP simulado.
