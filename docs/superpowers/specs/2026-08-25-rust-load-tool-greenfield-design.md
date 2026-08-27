# Load-tool greenfield em Rust

> Status: arquitetura de alto nível fechada. Permanecem abertos somente valores
> de qualificação e políticas operacionais detalhadas. O plano incremental está
> em
> [`../plans/2026-08-25-rust-load-tool-simulator.md`](../plans/2026-08-25-rust-load-tool-simulator.md).

## Contexto

O load-tool em Go cresceu junto com a descoberta da workload e acumulou
responsabilidades de pacing, networking, correlação, replays, evidências e
reporting. Agora que o contrato funcional está estável, o objetivo mudou: o
próprio gerador precisa ser previsível e interferir minimamente no sistema
medido.

Os diagnósticos recentes mostraram que o scheduler original já deixou de ser o
custo dominante, mas o runtime ainda manteve milhares de goroutines, alocou
aproximadamente 10,8 GiB durante um diagnóstico de seis minutos e apresentou
pausas de GC e scheduler correlacionadas com alguns buckets perdidos. A nova
implementação não será uma tradução linha a linha. O Go servirá apenas como
oráculo temporário de compatibilidade.

## Objetivos

- Sustentar pelo menos 2.000 pagamentos originais por segundo durante a janela
  ativa, mantendo os SLAs funcionais e temporais.
- Tratar os 2.000 TPS como PACS.008 originais. Replays, PACS.002 e Pulls gRPC
  são carga adicional.
- Produzir pacing suave em buckets de 10 ms com aproximadamente vinte
  requisições por bucket a 2.000 TPS.
- Usar deadlines absolutos no pacing e nunca recuperar slots PACS.008 originais
  atrasados.
- Isolar o pacer da preparação e da conclusão assíncrona do networking e da
  persistência de evidências; somente o `send_request` já preparado cruza a
  fronteira temporal nativa.
- Usar Tokio para HTTP/gRPC assíncrono e uma thread nativa dedicada ao pacing.
- Minimizar alocações no hot path e reutilizar conexões durante todo o run.
- Medir jitter, atraso até dispatch, slots perdidos, duração HTTP e overhead do
  próprio gerador.
- Priorizar, nesta ordem, simplicidade aderente ao objetivo do load-tool,
  manutenibilidade e performance/previsibilidade.

## Princípios

- A workload realizada nunca pode ser silenciosamente alterada pelo gerador.
- Slot de geração PACS.008 atrasado é perdido e contabilizado; nunca vira pico
  de catch-up.
- Nenhum PACS.008 original pode ser admitido depois do deadline de seu bucket.
- Correção funcional tem prioridade sobre uma medição de performance válida.
- Toda fila é bounded. Perda de evidência aborta o run; saturação de capacidade
  da workload invalida o experimento, mas continua sendo observada.
- Detalhes internos do runtime não entram no profile sem necessidade de
  negócio comprovada.
- A implementação mais simples que preserve o contrato é o padrão; otimizações
  só entram após profiling ou benchmark demonstrar necessidade material.

## Ambiente de execução do MVP

Para a qualificação final de performance, o load-tool deve preferencialmente
rodar em uma máquina separada da stack medida. Isso evita que o pacer, o
networking e a gravação de evidências disputem CPU com SPI, Kafka, PostgreSQL e
Notification Gateway. Execução no mesmo host continua adequada para
desenvolvimento e diagnósticos funcionais, mas um atraso do gerador causado por
contenção local invalida a medição de performance.

Quando a plataforma oferecer esse controle, recomenda-se também executar a
thread do pacer com prioridade de escalonamento apropriada e, idealmente, CPU
isolada. No Linux isso pode ser feito com políticas como `SCHED_FIFO` ou
`SCHED_RR`, mediante configuração cuidadosa e `CAP_SYS_NICE`; outras plataformas
possuem mecanismos diferentes. Essa configuração é responsabilidade do
ambiente de benchmark, não faz parte do profile e não é requisito de corretude
do load-tool no MVP.

## Pacer

Uma única thread nativa é responsável exclusivamente pelo relógio da geração
de PACS.008 originais:

```text
Instant monotônico
        ↓
deadlines absolutos
        ↓
sleep enquanto estiver longe
        ↓
spin curto no final
        ↓
mede lateness
        ↓
deadline aceitável?
   ↙             ↘
consome bucket    missed slots
preparado
   ↓
COMMITTED + send_request
   ↓
handoff único para Tokio
```

Ela não gera payloads, não constrói requests, não aguarda I/O, não agenda
replays e não escreve artefatos. Seu acesso ao estado limita-se ao `COMMITTED`
atômico, e sua participação no HTTP termina na chamada síncrona de
`send_request` já preparada.

### Distribuição inteira

Para taxa `TPS` e bucket `i`, zero-based:

```text
cumulative(i) = floor(i * TPS / 100)

requests(i) =
    cumulative(i + 1)
    - cumulative(i)
```

Assim, 2.100 TPS produzem exatamente:

```text
100 buckets × 21 = 2.100
```

Não há ponto flutuante, acumulador mutável ou drift. A implementação usa
aritmética inteira larga e verificada. Durações de geração são múltiplas de
10 ms.

### Deadlines

```text
bucketStart = phaseStart + i * 10 ms
bucketEnd   = bucketStart + 10 ms
```

O pacer avança diretamente ao bucket temporalmente vigente quando acorda
atrasado e nunca percorre buckets expirados um a um. O descritor é entregue
com uma antecedência interna fixa para que Tokio possa materializar o payload e
reservar o stream antes do início do bucket. Essa preparação não altera estado,
não escreve evidência e não inicia HTTP.

O spin final será pequeno e seu custo será medido. O valor inicial considerado
é 50 us, mas ele ainda precisa ser validado empiricamente antes de virar
decisão final.

## Fronteira pacer/Tokio

O pacer envia descritores compactos:

```text
BucketDescriptor {
    bucket_index,
    first_sequence,
    request_count,
    bucket_start,
    bucket_deadline
}
```

O canal é bounded somente para cobrir a janela fixa de preparação; ele não
autoriza backlog. Se o descritor não cabe no instante de preparação, seus slots
são perdidos; o pacer nunca move esse trabalho para outro bucket. Todo descritor
conserva seu deadline absoluto e a capacidade do canal não é parâmetro do
profile.

Tokio materializa payload, `Request` e reserva HTTP/2, registra as obrigações
causais determináveis e devolve um `PreparedBucket` ao pacer. O retorno pode
chegar fora de ordem, mas cada bucket só pode ser consumido em sua própria
fronteira temporal. Se ainda não estiver pronto em `bucketStart`, o pacer pode
aguardá-lo somente até `bucketDeadline`; depois disso seus slots são perdidos.

Na fronteira, a thread nativa executa apenas deadline check, publicação atômica
de `COMMITTED`, `send_request` já preparado e um único handoff do bucket para
Tokio. Replays, conclusão HTTP, evidências e bookkeeping permanecem no runtime
assíncrono. Isso elimina o reagendamento Tokio provocado pelo antigo gate sem
transformar o pacer em worker de aplicação.

O desenho recomendado para a primeira implementação é uma task Tokio por
request iniciado. Isso oferece elasticidade natural sem manter milhares de
workers ociosos. Pools fixos de tasks e actors por PSP foram descartados por
adicionarem dimensionamento ou coordenação desnecessários.

O PACS.008 original não possui semáforo de concorrência. O pacer limita a taxa,
o deadline do bucket impede carry-over e o timeout HTTP limita a vida de cada
task. Juntos, esses mecanismos já impõem um limite matemático à concorrência.
O PACS.008 replay também não precisa de um limite próprio: sua população e seu
ritmo derivam deterministicamente dos originais iniciados.

O gerador mantém apenas contadores de `current_in_flight` e
`maximum_in_flight` para observar esses fluxos. Uma concorrência incompatível
com o schedule e os timeouts é defeito do gerador, não uma situação a ser
mascarada por throttling adicional.

PACS.002 é diferente: ele nasce de notificações recebidas e o SPI pode liberar
um backlog em rajada. PACS.002 original e replay compartilham uma única
capacidade bounded de HTTP causal. A aquisição é não bloqueante e acontece
imediatamente antes do início HTTP; sleepers aguardando `replayDelay` não
consomem permit.

Esgotar essa capacidade produz `generator capacity violation` e invalida o run.
O load-tool não espera, não cria uma fila ilimitada e não reduz silenciosamente
a carga original. Também não tenta mascarar backlog produzido pelo SPI.

O limite é um safety budget interno, definido pela qualificação do gerador e
registrado junto com o pico observado. Não é configuração do profile, parâmetro
de negócio, função de `replayDelay` ou throttling adaptativo baseado na latência
observada durante o experimento.

## Hot path de PACS.008

```text
startup/warmup
    ↓
pré-computa templates e dados estáveis
pré-aquece conexões
    ↓
pacer envia descriptor
    ↓
Tokio task
    ↓
deadline check inicial
    ↓
materialização mínima do PACS.008
    ↓
aguarda readiness/capacidade HTTP/2
somente até bucketDeadline
    ↓
constrói Request e devolve PreparedBucket
    ↓
pacer aguarda bucketStart
    ↓
deadline check final
    ↓
COMMIT do pagamento e das obrigações causais
    ↓
send_request
    ↓
um handoff do bucket para Tokio
```

`COMMIT` é a publicação semântica do pagamento como `COMMITTED` em
`PaymentState`, junto com todas as obrigações causais já determináveis. Não é
uma transação de banco de dados.

Antes do `COMMIT`, qualquer falha em cumprir `bucketDeadline` transforma o slot
em `missed`, sem estado, linha de evento ou request observável associado. Depois
do `COMMIT`, o request nunca mais é descartado por pacing. Falha imediata ou
timeout de `send_request` é resultado HTTP do pagamento já admitido.

A disponibilidade de stream HTTP/2 faz parte da admissão. Espera por readiness
termina em `bucketDeadline` e precisa reservar capacidade para o `send_request`
imediatamente seguinte, não apenas observar disponibilidade momentânea. A
implementação não pode esconder requests admitidos numa fila interna do cliente
HTTP/2 depois desse instante.

O instante de início HTTP é a fronteira imediatamente anterior à submissão ao
cliente HTTP/2. Não pretende medir o primeiro byte no socket.

## Seleção de replays

PACS.008 e PACS.002 usam populações e domínios independentes, mas nenhuma delas
depende de ordinal atribuído pela ordem de scheduling do Tokio. A seleção é
derivada deterministicamente da `sequence`, do cenário e de uma constante de
domínio (`PACS008` ou `PACS002`). HTTP 2xx não participa da elegibilidade.

`replayShare` possui granularidade de um ponto percentual. O profile aceita
somente valores maiores que zero e menores ou iguais a um para os quais
`replayShare * 100` seja inteiro. Assim, cada bloco completo de cem mensagens
elegíveis seleciona exatamente `replayShare * 100` replays.

Para PACS.008, o ordinal determinístico da população é a própria `sequence`. Um
slot selecionado só cria obrigação se chegar ao `COMMIT`; um slot perdido torna
o run inválido e não é substituído por outro pagamento.

Para PACS.002, uma função pura deriva um ordinal denso entre os pagamentos cujos
cenários produzem PACS.002 pelo simulador:

```text
eligiblePerBlock = quota dos cenários aplicáveis em cada bloco de 100
eligibleBefore   = posições aplicáveis anteriores dentro do bloco da sequence
pacs002Ordinal   = sequenceBlock * eligiblePerBlock + eligibleBefore
```

`eligibleBefore` é calculado somente a partir da permutação determinística do
bloco e do contrato dos cenários. Não existe contador global ou estado mutável
para essa população.

Num run válido, isso preserva a proporção exata nas duas populações. Slot
PACS.008 perdido ou falha de admissão PACS.002 invalida o run e não provoca
seleção compensatória.

Não haverá RNG mutável, seed ou shuffle. Dado o ordinal determinístico da
população, a seleção é uma função pura O(1):

```text
block    = ordinal / 100
position = ordinal % 100
rotation = stableHash(domain, block) % 100
shuffled = (position * 37 + rotation) % 100
quota    = integer(replayShare * 100)
selected = shuffled < quota
```

Como 37 é coprimo de 100, cada bloco completo percorre as cem posições
exatamente uma vez. Share de 5% seleciona exatamente cinco posições por bloco,
sem concentração periódica óbvia.

`stableHash` é um mixer inteiro interno, versionado, com constantes de domínio
fixas e vetores de teste. A implementação não pode usar `DefaultHasher`, estado
aleatório do processo ou qualquer algoritmo cuja saída possa variar entre
execuções.

Quando selecionado, o payload é construído uma vez e seu conteúdo é
compartilhado com o replay. O replay é agendado para
`requestStarted + configuredDelay` antes do submit original.

### Execução dos replays

Cada replay selecionado possui uma task Tokio própria. Não haverá scheduler
central, heap ou timer gerenciado pelo load-tool:

```text
seleção
    ↓
registra obrigação causal
    ↓
tokio::spawn
    ↓
sleep_until(requestStarted + configuredDelay)
    ↓
PACS.002 replay tenta adquirir capacidade HTTP causal compartilhada
PACS.008 replay segue diretamente
    ↓
POST usando o mesmo Bytes
    ↓
conclui obrigação
```

Tasks em `sleep_until` não ocupam threads nem capacidade HTTP causal; usam o
timer do Tokio.

Regras:

- a task e sua obrigação existem antes do submit da mensagem original;
- HTTP 2xx não participa da elegibilidade;
- o replay nunca começa antes do delay configurado;
- original e replay usam corpos byte a byte iguais;
- cada mensagem original cria no máximo uma obrigação de replay do mesmo tipo;
- PACS.008 repetido não cria outro PACS.002 original;
- PACS.002 original iniciado durante o drain não cria uma nova obrigação de
  replay;
- replays já selecionados antes de `generationEnd` podem executar no drain;
- todo replay deve terminar até o `hardDeadline` de sua fase;
- trabalho ainda pendente no `hardDeadline` vira violação e é cancelado.

Ao observar pela primeira vez um PACS.008 no receiver, a tabela de estado cria
imediatamente a obrigação causal para o PACS.002 original. Sua seleção de replay
já é derivável da `sequence`; se aplicável e fora do drain, a obrigação do
replay é registrada depois da admissão na capacidade HTTP causal e antes de o
status original começar de fato.

## Planejamento derivado da sequência

A sequência do slot planejado identifica o pagamento. O `EndToEndId` contém um
prefixo exclusivo do run e essa sequência. Uma função pura deriva os demais
dados:

```text
derivePayment(sequence, profile)
→ phase
→ scenario
→ pair
→ amount
→ expected outcome
```

Para cenários 80/20:

```text
block    = sequence / 100
position = sequence % 100
rotation = stableHash("scenario", block) % 100
rank     = (position * 37 + rotation) % 100

rank 0..79  → happy-path
rank 80..99 → insufficient-funds
```

O ordinal interno do cenário permite derivar hot/cold, par e valor com
aritmética inteira. `hotTrafficShare` aceita pontos percentuais inteiros e cada
bloco completo de 100 pagamentos daquele cenário respeita exatamente essa
proporção, sem estado mutável ou aleatoriedade. Payer, receiver, fase, timeout e
expectativa não são copiados para cada pagamento.

Slots perdidos nunca são ativados. Em um run inválido, a proporção efetivamente
iniciada pode variar levemente por causa desses buracos; o relatório deve
mostrar a workload realizada.

## Estado dos pagamentos

A quantidade máxima de slots é conhecida antes da execução. O estado é uma
tabela contígua `Vec<AtomicU8>` indexada pela sequência planejada.

Flags iniciais:

```text
COMMITTED
PACS002_CLAIMED
EXPECTED_OUTCOME_SEEN
CONTRADICTION_SEEN
```

No profile de 15 minutos são aproximadamente 2.010.000 slots, ou 1,92 MiB. Em
uma execução de 24 horas a 2.100 TPS seriam aproximadamente 173,1 MiB. Não há
strings, payloads, timestamps ou objetos de cenário nessa tabela.

Os atomics, bitmasks e `Ordering`s ficam encapsulados numa API pequena de
`PaymentState`. O restante do simulador expressa operações semânticas como
`commit`, `claimPacs002` e `observeOutcome`, sem manipular flags diretamente.

A primeira notificação PACS.008 no receiver usa internamente
`fetch_or(PACS002_CLAIMED)`. Quem ganha o claim tenta criar e admitir exatamente
um PACS.002; redelivery idêntica vira no-op lógico. Se a admissão falhar, o run
já é inválido. O claim não sofre rollback e não será criada uma máquina de
estados adicional para tentar recuperá-lo.

Notificação PACS.002 no payer valida PSP, status e motivos derivados da
sequência. A primeira observação correta conclui a obrigação; repetições
corretas são aceitas segundo at-least-once; qualquer resultado contraditório
invalida o run, inclusive se chegar depois do correto.

Não haverá padding ou sharding preventivo. Essa organização só muda se um
perfil demonstrar false sharing material.

## Pull de notificações e PACS.002

Cada identidade PSP possui um cliente Tonic mTLS persistente e no máximo um
`PullNotifications` unary em andamento. O primeiro request usa cursor vazio; os
seguintes reapresentam o `nextCursor` emitido pelo Gateway.

O tamanho do lote não é enviado pelo PSP. O contrato do protocolo permite ao
Gateway devolver imediatamente até 15 notificações. O simulador conserva as
estatísticas atuais de lotes não vazios (`count`, `mean`, `p50`, `p95`, `max`),
respostas vazias e violações do máximo do protocolo.

Uma resposta é tratada nesta ordem:

```text
validar todas as mensagens do lote
    ↓
correlacionar e registrar as notificações do run atual
    ↓
registrar deterministicamente os PACS.002 causais aceitos
    ↓
avançar o cursor em memória
    ↓
emitir o próximo Pull
```

O cursor nunca avança após processamento parcial. Falha de validação, recorder
ou admissão na capacidade HTTP causal invalida o run antes do avanço. Redelivery
do mesmo lote é seguro porque `PaymentState` permite fazer claim de no máximo um
PACS.002 original por pagamento.

O avanço do cursor não espera a conclusão HTTP dos PACS.002 já registrados. A
capacidade HTTP causal limita esse trabalho assíncrono sem bloquear o loop de
Pull. O timeout do PACS.002 é derivado da fase do pagamento original. Pulls
permanecem ativos durante warmup, active e drain.

O `requestStarted` do PACS.002 é capturado somente depois que o stream HTTP/2
está disponível e o request foi preparado. Nesse ponto, a obrigação de replay
aplicável é registrada e, imediatamente depois, o `send_request` começa. Assim,
a espera por readiness não contamina o timestamp e o replay continua existindo
antes do efeito observável do status original.

## Payloads, buffers e conexões

A primeira implementação usa Hyper/Hyper-util, Rustls e Tonic. Não usa Reqwest.

- Um cliente HTTP/2 mTLS é criado por identidade PSP.
- ALPN aceita somente `h2`.
- As conexões são pré-aquecidas antes da geração e permanecem reutilizáveis.
- URIs, headers e dados estáveis são pré-computados.
- A primeira implementação usa estruturas tipadas e serialização direta para
  um buffer pré-dimensionado, sem `String` intermediária.
- O corpo pode ser convertido em `Bytes`; clones para original/replay são O(1).

Encoder manual com fragmentos estáticos e conversão por `itoa` só substitui a
serialização tipada se profiling e microbenchmark com o payload real mostrarem
benefício material. Não faz parte da arquitetura inicial.

Da mesma forma, a primeira implementação não possui `BufferPool`. Se alocação
de payload aparecer como custo material, um microbenchmark compara alocação por
payload com um pool bounded usando o encoder real e retenção representativa de
replay. O pool só entra se vencer; nenhuma regra da arquitetura depende dele.

## Instrumentação mínima

O funil observado é:

```text
slots planejados
    ↓
entregues ao Tokio
    ↓
HTTP iniciado
    ↓
HTTP concluído
```

Distribuições temporais:

- `pacer_lateness`: atraso da thread em relação ao início planejado do bucket;
- `dispatch_lateness`: atraso até o Tokio receber o descriptor;
- `http_duration`: duração do request iniciado.

`http_start_lateness` é reconstruído por:

```text
plannedOffset = bucketIndex * 10 ms
startLateness = actualStartOffset - plannedOffset
```

Os cálculos de jitter usam `Instant` monotônico. Timestamps civis existem
somente para correlação externa e artefatos.

No início do run é capturado um par imutável:

```text
runMonoOrigin = Instant::now()
wallOrigin    = SystemTime::now()
```

Cada fase possui seu próprio `phaseStart` e seus deadlines. Pacing usa esses
instantes monotônicos; eventos preservam offsets relativos a `runMonoOrigin`.
O recorder e o `run-window.json` projetam esses offsets sobre `wallOrigin` para
produzir os timestamps absolutos esperados pelos artefatos. Mudança no relógio
civil durante a execução não desloca nenhuma janela.

Overhead adicional do gerador:

- CPU total do processo;
- RSS máximo;
- wall-time total de spin;
- máximo de requests in-flight.

CPU específica da thread do pacer é diagnóstico opcional quando a plataforma
permitir medi-la de forma barata e confiável. Os sinais essenciais do pacer são
`pacer_lateness`, slots perdidos e wall-time de spin.

Não haverá taxonomia pública de cada deadline check, instrumentação contínua do
allocator, profundidade de todos os canais ou histogramas concorrentes no hot
path.

## Recorder de evidências

Todas as tasks produtoras compartilham um único canal MPSC bounded e
pré-alocado. Uma única thread nativa recebe eventos compactos e escreve os
quatro artefatos:

```text
HTTP/gRPC tasks
    ↓ try_send(Event)
canal bounded
    ↓
recorder thread
    ├─ pacs008-starts.csv
    ├─ pacs002-starts.csv
    ├─ notifications.csv
    └─ replays.csv
```

Eventos carregam índices, enums e inteiros, não strings já formatadas. Por
exemplo:

```text
Pacs008Completed {
    sequence,
    created_offset_ns,
    started_offset_ns,
    done_offset_ns,
    http_status,
    replay_selected
}
```

A thread deriva `EndToEndId`, participantes, cenário e representações textuais
usando o plano imutável. Ela mantém quatro `BufWriter`s grandes e reutiliza um
buffer de formatação. Não há flush ou fsync por evento; o fechamento drena o
canal, faz flush e sincroniza os arquivos.

Na etapa intermediária, nomes, headers e semântica das colunas permanecem
compatíveis com os quatro CSVs atuais. Eventos compactos carregam ou permitem
derivar todos os valores necessários; a projeção do relógio produz os campos
absolutos em nanossegundos. A ordem física entre rows concorrentes não faz parte
do contrato e o report deve ordenar quando uma análise temporal exigir.

Produtores usam somente `try_send`. Canal cheio, falha de escrita ou falha no
flush comprometem a integridade das evidências e abortam o run; eventos nunca
são descartados silenciosamente.

Uma thread é suficiente como primeira implementação. Writers paralelos e log
binário durante a carga só serão considerados se profiling demonstrar que o
recorder ainda interfere materialmente na geração.

## Lifecycle da execução

### Hard deadlines

Cada macrofase possui um único `hardDeadline`:

```text
warmupHardDeadline = warmupPlannedEnd + completionTimeout
activeHardDeadline = generationEnd + drain
```

`activeHardDeadline` também é exposto como `replayDeadline` nos artefatos
atuais. O deadline efetivo de qualquer HTTP original, causal ou replay é:

```text
httpDeadline = min(requestStart + causalTimeout, hardDeadline)
```

Essa é a mesma regra em warmup, active e drain. O deadline do bucket controla
somente a admissão do PACS.008 original; depois do `COMMIT`, seu HTTP obedece a
`httpDeadline`.

### Startup

```text
validar inputs
    ↓
calcular quantidade máxima de slots
    ↓
pré-alocar PaymentState
    ↓
iniciar recorder
    ↓
criar e preaquecer clientes HTTP/2 e gRPC
    ↓
capturar runMonoOrigin + wallOrigin
    ↓
iniciar thread do pacer
```

O pacer permanece vivo durante o run e recebe planos imutáveis. Bootstrap e
steady são entregues juntos para permanecerem contíguos. Active recebe uma nova
`phaseStart` somente depois da conclusão do gate; `runMonoOrigin` permanece o
mesmo durante toda a execução.

### Warmup

O contrato vigente permanece:

```text
bootstrap: 500 TPS / 60 s / timeout HTTP de 30 s
steady:  1.500 TPS / 60 s / timeout HTTP de 5 s
```

Os dois estágios compartilham um contador pequeno de obrigações observáveis:

- HTTP do PACS.008 original `COMMITTED`;
- outcome esperado;
- replay PACS.008 selecionado;
- PACS.002 original criado;
- replay PACS.002 selecionado.

Toda continuação é registrada antes de a ação que a originou terminar. O gate
abre somente quando a geração de warmup foi fechada e o contador chegou a zero.
Se isso não ocorrer até o deadline absoluto, active não começa.

O contador semântico existe somente para o gate de warmup. Ele não se torna uma
árvore genérica de obrigações.

### Active

Depois do gate:

```text
activeStart    = Instant::now()
generationEnd  = activeStart + duration
```

Esses instantes são fixados antes da geração e nunca são deslocados. Suas
projeções civis são persistidas fora da thread do pacer, sem fazer a geração
esperar por I/O de filesystem. O `hardDeadline` segue a regra comum definida
acima. O pacer recebe o plano monotônico ativo completo.

### Drain

Ao chegar em `generationEnd`:

- nenhum PACS.008 original novo pode começar;
- HTTPs já iniciados podem terminar;
- outcomes continuam sendo consumidos;
- PACS.002 causal ainda pode ser enviado;
- PACS.002 iniciado no drain não ganha replay novo;
- replays selecionados anteriormente ainda podem executar;
- Pulls gRPC permanecem ativos.

O drain possui duração fixa e não termina antecipadamente. Mesmo que todas as
obrigações conhecidas terminem antes, o run observa até o deadline para detectar
outcomes contraditórios e redeliveries tardias.

No `hardDeadline` da fase ativa, exposto como `replayDeadline`:

```text
proibir semanticamente a criação de novo trabalho
    ↓
cancelar trabalho restante
    ↓
fechar Pulls
    ↓
TaskTracker.close()
    ↓
TaskTracker.wait()
    ↓
fechar canais do recorder
    ↓
flush dos artefatos
    ↓
report
```

`TaskTracker` serve somente ao lifecycle técnico e não conserva resultados de
tasks já concluídas. Assim é possível esperar todos os produtores antes de
fechar o recorder sem acumular milhões de `JoinHandle`s. Ele não determina
corretude, conclusão de obrigação ou validade do run. Na janela ativa, outcomes
ausentes e divergências entre a quantidade selecionada e executada de replays
são determinados pelo report a partir dos artefatos completos. Identidade,
timing e igualdade do payload de cada replay pertencem aos testes do gerador,
não à qualificação de uma execução.

## Migração

### Protótipo de qualificação

O Rust implementa somente o simulador. Ele consome o bundle preparado atual:

```text
inputs/profile.json
inputs/execution-plan.json
certificados efêmeros do run
```

E produz eventos e `run-window.json` compatíveis com o relatório Go, além de
`diagnostics/loadtool/generator-metrics.json`.

Durante essa etapa:

```text
Go
├─ valida profile
├─ produz execution-plan
├─ prepara ambiente/funding
└─ renderiza sla-report.json

Rust
└─ executa exclusivamente o simulador
```

Um adaptador Go interno e temporário foi usado somente para o A/B e removido
depois que o candidato não atingiu o piso de geração. O caminho público
permanece integralmente no Go; não há engine selector nem suporte permanente a
dois simuladores.

### Estado final condicionado

Depois de o simulador Rust provar equivalência funcional e vantagem operacional,
Rust também assume:

```text
validate-profile
execution plan
simulate
report
run
```

O runner chamará somente Rust e o load-tool Go será removido apenas se uma
qualificação futura satisfizer esses critérios. O A/B de 26 de agosto de 2026
não autorizou esse cutover.

## Resultado da qualificação de 26 de agosto de 2026

No mesmo profile diagnóstico e com stack recriada para cada candidato, o Go
produziu média de `2.098,967 TPS` e mínimo rolling de `2.058 TPS`. O candidato
Rust final medido depois das correções funcionais produziu média de
`1.748,433 TPS`, mínimo rolling de `1.496 TPS` e perdeu `30.877` dos `246.000`
slots planejados.

As métricas separaram `15.819` slots não despachados pelo pacer de `15.058`
admissões que acordaram depois do deadline. Na versão então testada, o p99 de
lateness da thread nativa foi `2,177 ms`, maior que o envelope inteiro de
`1 ms` usado por aquela implementação. Não houve violação da
capacidade HTTP causal, e todos os pagamentos efetivamente iniciados tiveram
outcome funcional válido; o defeito está na previsibilidade do gerador sob esse
envelope, não na semântica dos cenários.

A inspeção do transporte também mostrou que `hyper::http2::SendRequest::ready`
valida o dispatcher, mas não reserva de forma observável um stream HTTP/2; seu
dispatcher interno é unbounded. Não houve sinal de saturação causal neste A/B,
mas o protótipo também não provou a invariante de ausência de backlog escondido
no cliente. Uma retomada precisa resolver ou limitar explicitamente essa
fronteira, em vez de assumir que `ready()` equivale a stream reservado.

Decisão: o protótipo Rust é preservado para estudo, o Go continua sendo a
implementação ativa e o run de 15 minutos/cutover não é executado. Uma retomada
precisa primeiro rever, explicitamente, a granularidade de pacing ou a regra de
validade de misses; não deve esconder a deficiência com catch-up.
