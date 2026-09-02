# Metodologia do load test

Este documento responde a uma pergunta: **como o benchmark produz e mede uma carga temporalmente válida sem deixar que o próprio gerador esconda atrasos do sistema ou dele mesmo?**

Os resultados finais, o ambiente e os limites da qualificação estão em [Performance e evidência](../performance.md). Aqui o foco é o contrato metodológico da ferramenta.

## O que o gerador representa

O load tool simula as instituições que participam do fluxo:

```text
pagador
  │
  │ pacs.008 original
  ▼
Payment Ingress
  │
  │ confirmação pelo Gateway
  ▼
recebedor simulado
  │
  │ pacs.002
  ▼
Payment Ingress
  │
  │ outcome final pelo Gateway
  ▼
pagador simulado
```

Sua responsabilidade é:

* iniciar `pacs.008` originais nos instantes planejados;
* reagir à solicitação recebida criando a `pacs.002` correspondente;
* executar as repetições selecionadas;
* consumir as confirmações finais;
* registrar os instantes e resultados necessários para o relatório.

Ele não decide se o sistema processou um pagamento corretamente apenas porque recebeu HTTP `2xx`. Essa resposta confirma o ingresso da mensagem; o outcome final ainda precisa voltar pelo Notification Gateway.

Também não consulta o estado interno do SPI para inferir quiescência ou sucesso. O benchmark afirma somente o que consegue observar e contabilizar de forma determinística.

## O perfil vira um plano determinístico

Antes da execução, o perfil é validado e convertido em um plano normalizado. O diretório de resultado preserva tanto o perfil original quanto esse plano efetivamente executado.

A partir da sequência numérica de cada pagamento, o planner deriva:

* cenário;
* par de participantes;
* valor;
* expectativa de outcome;
* elegibilidade para `pacs.002`;
* seleção das repetições.

As proporções são representadas em blocos determinísticos de 100 posições. Assim, uma participação de 80% significa exatamente 80 posições por bloco, distribuídas sem depender da ordem em que tasks Tokio forem escalonadas.

A seleção de replay usa a mesma ideia, mas separa os domínios `pacs.008` e `pacs.002`. Uma mensagem é selecionada pela sua sequência — ou pelo ordinal determinístico dentro da população que produz `pacs.002` — e não por um contador mutável atualizado durante a execução.

Isso torna a carga reproduzível: scheduling concorrente não altera qual pagamento pertence a um cenário, usa um par quente ou recebe replay.

## A carga é open loop

Em um gerador de malha fechada, uma resposta lenta reduz naturalmente a taxa de novas requisições. Isso mede quantos clientes sequenciais o sistema atende, mas não prova que ele sustentou uma taxa externa independente da própria latência.

O load tool usa malha aberta (*open loop*). Cada `pacs.008` original recebe antecipadamente uma janela temporal absoluta. Se a ferramenta não conseguir iniciá-lo nessa janela, o pagamento vira um slot perdido.

Ele não é deslocado para frente.

```text
janela perdida
        ↓
pagamento não iniciado
        ↓
diferença permanece no relatório

não existe:

janela perdida
        ↓
fila de atraso
        ↓
rajada posterior para recuperar a média
```

Essa ausência de *catch-up* é essencial. Sem ela, o gerador poderia ficar abaixo da taxa durante parte da execução e compensar depois, fazendo uma média correta representar uma carga temporalmente incorreta.

## Buckets absolutos de 10 ms

O pacing divide cada segundo em 100 buckets absolutos de 10 ms.

O número de requisições do bucket `i` é calculado por aritmética inteira cumulativa:

```text
floor((i + 1) × TPS / 100)
-
floor(i × TPS / 100)
```

Para 2.100 pagamentos por segundo, cada bucket recebe exatamente 21 pagamentos. Para taxas que não dividem 100, os buckets maiores são distribuídos ao longo do segundo sem ponto flutuante e sem drift acumulado.

Uma thread nativa dedicada controla esses deadlines usando `Instant`, o relógio monotônico do Rust. Ela dorme enquanto o deadline está distante e faz um spin curto nos 50 microssegundos finais.

Os deadlines são sempre derivados do início absoluto da fase. O tempo gasto processando um bucket não redefine o deadline do próximo.

## Preparar antes não significa admitir antes

Materializar payloads, reservar capacidade HTTP/2 e aguardar scheduling assíncrono em cima do deadline produziria jitter desnecessário.

Por isso, o pacer anuncia cada bucket ao runtime assíncrono até 50 ms antes de sua janela. Tokio pode então:

1. derivar os pagamentos daquele bucket;
2. materializar os payloads;
3. reservar capacidade real de stream HTTP/2;
4. devolver o bucket preparado ao pacer.

Preparação não conta como início do pagamento.

Na janela do bucket, o pacer faz somente a admissão final do material já preparado. A regra é:

> Nenhum `pacs.008` original pode ser admitido depois do deadline de seu bucket.

O fluxo efetivo é:

```text
deadline check durante a preparação
        ↓
materializa payload
        ↓
reserva capacidade HTTP/2 até o deadline do bucket
        ↓
deadline check final
        ↓
marca o pagamento como COMMITTED no gerador
        ↓
send_request
```

Antes de `COMMITTED`, o pagamento ainda pode virar um slot perdido. Depois de `COMMITTED`, ele não é mais descartado por pacing: a tarefa acompanha a resposta e as obrigações causais até seu deadline do experimento.

## Capacidade HTTP/2 precisa ser real

Uma conexão HTTP/2 possui um limite de streams concorrentes anunciado pelo servidor. Apenas verificar se o client local parece pronto não garante que uma nova stream possa começar imediatamente; a biblioteca poderia aceitar trabalho e mantê-lo em uma fila interna.

O gerador lê o limite anunciado pelo servidor e mantém um permit para cada stream disponível. A preparação só termina quando consegue reservar um desses permits antes do deadline do bucket.

O permit permanece associado à requisição até a resposta terminar ou a stream ser cancelada.

Isso impede que a fila interna do client vire backlog escondido:

```text
sem capacidade antes do deadline
→ slot perdido

capacidade reservada + admissão final dentro da janela
→ request realmente iniciada
```

As conexões são persistentes, usam TLS com autenticação mútua e exigem HTTP/2 negociado por ALPN. Antes do warmup, cada client executa um `GET /health` na mesma conexão para confirmar o transporte e criar a capacidade de streams.

## O pacer não executa o fluxo inteiro

A thread do pacer não espera respostas HTTP, não processa notificações e não escreve o relatório.

As responsabilidades ficam separadas:

| Parte | Responsabilidade |
| --- | --- |
| planner | deriva workload e payloads a partir da sequência |
| pacer nativo | controla deadlines absolutos e a admissão dos originais |
| Tokio | executa HTTP/2, Pulls, `pacs.002` e replays |
| recorder | grava os eventos em uma única thread |
| reporter | agrega os eventos depois da execução |

O pacer usa um canal bounded para solicitar a preparação dos próximos buckets. Se acordar tarde, o canal estiver cheio, o bucket não voltar preparado ou a admissão HTTP perder o deadline, a causa é contabilizada como diagnóstico de slot perdido.

Esses contadores ajudam a explicar uma execução, mas não substituem a medição final. O efeito objetivo continua aparecendo no número de originais efetivamente iniciados e na menor janela contínua de um segundo.

## Estado causal pequeno e pré-alocado

O gerador precisa saber se um pagamento realmente foi admitido e se a solicitação recebida já criou sua resposta.

Ele mantém um `Vec<AtomicU8>` pré-alocado, indexado pela sequência. A API expõe dois fatos:

* `COMMITTED`: a requisição original adquiriu capacidade e começou dentro de sua janela;
* `PACS002_CLAIMED`: a primeira notificação elegível conquistou o direito de criar a resposta.

A primeira solicitação recebida faz o claim da `pacs.002`; entregas compatíveis repetidas não criam respostas originais adicionais.

Essa estrutura não reproduz a máquina de estados do SPI. Ela guarda somente o mínimo que o gerador precisa para coordenar seu próprio trabalho.

## Trabalho causal possui limite próprio

Uma `pacs.002` e seu replay não pertencem ao pacing dos originais. Elas nascem como consequência de notificações recebidas.

O gerador mantém uma capacidade bounded compartilhada para esse HTTP causal. A aquisição é não bloqueante:

```text
capacidade disponível
→ cria a task causal

capacidade esgotada
→ generator capacity violation
→ execução operacionalmente inválida
```

Ele não reduz adaptativamente a taxa nem mascara backlog produzido pelo sistema.

Tasks que aguardam o delay de replay não seguram um permit. A capacidade só é ocupada quando o replay realmente tenta iniciar seu HTTP.

## Warmup espera obrigações observáveis

O warmup possui duas etapas de geração e um gate antes da fase ativa.

Quando um pagamento de warmup é planejado, o gerador já conhece suas obrigações futuras:

* conclusão do HTTP original;
* outcome final esperado;
* `pacs.008` replay, quando selecionado;
* `pacs.002` original, quando o cenário a produz;
* `pacs.002` replay, quando selecionado.

Essas obrigações são registradas antes de o pagamento ser admitido. Assim, um outcome não consegue reduzir momentaneamente o contador a zero antes que um callback posterior registre seu replay ou sua resposta.

Depois que a geração do warmup termina, o gate fecha a criação de roots e espera o contador chegar a zero. Falha, contradição ou timeout interrompe a execução.

O gate não promete que Kafka, PostgreSQL e todas as filas internas estão completamente ociosos. Ele garante algo menor e verificável:

> A fase ativa não começa enquanto houver trabalho observável de warmup ainda pendente no load tool.

## Replays são carga adicional

Os replays selecionados preservam os mesmos bytes da mensagem original e são enviados depois do delay configurado.

Eles não ocupam slots de `pacs.008` original e não contam para o piso de throughput. Também não são disparados durante o drain apenas para completar uma proporção planejada que não nasceu durante a geração válida.

O relatório compara quantos replays foram selecionados, iniciados e aceitos. Uma seleção não executada ou uma resposta fora de `2xx` aparece como violação de replay.

## Deadlines e encerramento

Cada fase possui um deadline final:

```text
warmup hard deadline
= fim planejado do warmup + completion timeout

active hard deadline
= fim da geração ativa + drain
```

O deadline de cada HTTP é o menor valor entre seu timeout causal e o hard deadline da fase.

Depois que a geração ativa termina, o gerador observa sempre o drain completo. Não existe encerramento antecipado apenas porque os outcomes conhecidos chegaram mais cedo.

No hard deadline:

1. a criação semântica de novo trabalho é proibida;
2. Pulls e tasks remanescentes são cancelados;
3. o `TaskTracker` é fechado e aguardado;
4. o recorder é fechado, faz flush e sincroniza os arquivos;
5. o relatório é construído a partir dos eventos completos.

O `TaskTracker` controla lifecycle técnico. Corretude e completude continuam sendo determinadas pelos eventos e pelas expectativas do relatório.

## O recorder não participa do pacing

As tasks produzem eventos pequenos e os enviam para uma fila bounded. Uma thread single-writer grava quatro CSVs:

* originais `pacs.008`;
* respostas `pacs.002`;
* notificações observadas;
* replays executados.

O caminho de geração não calcula percentis nem janelas de throughput.

Se a fila do recorder encher ou sua thread falhar, a execução é interrompida. Eventos não são descartados silenciosamente para preservar a aparência de uma run saudável.

O relatório só é produzido depois que o recorder termina e os arquivos são sincronizados. Ele não sobrescreve um relatório existente.

## Como o relatório interpreta os eventos

### Throughput

Um original conta apenas se seu `request_started_at` estiver dentro da fase ativa.

O relatório ordena esses instantes e avalia todas as janelas contínuas de um segundo completamente contidas na fase. O menor número encontrado é o `minimum_rolling_tps`.

Por isso:

* a média não consegue esconder um vale;
* uma rajada posterior não repara uma janela anterior;
* planejados e executados permanecem separados;
* slots perdidos continuam visíveis.

### Latência end-to-end

A latência começa em `request_started_at`, quando o HTTP original realmente começa, e termina na primeira confirmação final compatível observada pelo pagador.

Tempo de preparação, espera anterior à admissão e a resposta HTTP do ingresso não encerram essa medição.

Os percentis usam somente originais da fase ativa que receberam outcome final compatível.

### Corretude observável

Para cada pagamento aceito no ingresso, o relatório compara as confirmações do pagador com a expectativa do cenário.

Ele conta:

* outcome compatível;
* outcome ausente;
* status incompatível;
* reason codes incompatíveis;
* respostas causais fora de `2xx`;
* replays selecionados que não foram executados ou aceitos.

Duplicatas finais compatíveis são permitidas pela semântica at-least-once. Qualquer confirmação incompatível permanece uma contradição, mesmo que uma confirmação correta também tenha chegado.

O relatório não contém um veredito geral chamado `valid`. A qualificação resulta da leitura conjunta de throughput, p99 e violações contra o contrato documentado em [Performance e evidência](../performance.md). Já uma falha operacional do próprio gerador — capacidade esgotada, recorder perdido, transporte inválido ou erro interno — encerra o comando com erro.

## Preparação e execução são fases diferentes

O script de preparação possui a responsabilidade sobre o ambiente:

1. valida o perfil e gera o plano normalizado;
2. remove stack e volumes anteriores;
3. constrói e inicia uma stack nova;
4. espera os serviços ficarem disponíveis;
5. captura a configuração efetiva do SPI;
6. provisiona os participantes;
7. gera os certificados mTLS da carga;
8. publica um único ambiente preparado como `current`.

Ele não gera pagamentos.

O runner exige que o perfil solicitado seja exatamente o perfil preparado. Em seguida, copia perfil, plano e configuração para um novo diretório de resultado, inicia os diagnósticos configurados e executa o binário Rust.

O runner não tenta provar quiescência interna da stack nem repetir readiness. Essa responsabilidade pertence à preparação.

Nas qualificações finais, cada execução foi precedida por uma preparação completa e independente.

## Limites metodológicos

O desenho atual assume conscientemente que:

* gerador e sistema compartilham o mesmo host;
* a thread do pacer não usa prioridade de tempo real nem afinidade fixa de CPU;
* 10 ms é a granularidade de pacing, portanto requisições dentro do mesmo bucket não possuem espaçamento individual contratado;
* o gate de warmup conhece obrigações do load tool, não todo trabalho interno da stack;
* a corretude end-to-end observa outcomes e replays, mas não relê todos os saldos finais do PostgreSQL;
* diagnósticos de JVM, containers e PostgreSQL ajudam a investigar uma execução, mas não substituem os critérios do relatório.

Esses limites impedem extrapolações indevidas sem enfraquecer a propriedade central da metodologia: **a ferramenta só atribui throughput ao trabalho que realmente começou dentro de sua janela temporal, e mede o resultado final do mesmo pagamento que iniciou**.

## Verificação no repositório

O pacing e a admissão estão em:

* [`pacer.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/pacer.rs);
* [`original.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/original.rs);
* [`http2.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/http2.rs).

O lifecycle e o workload determinístico estão em:

* [`simulator.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/simulator.rs);
* [`phase_tracker.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/phase_tracker.rs);
* [`planner.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/planner.rs);
* [`replay.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/replay.rs).

A coleta e a interpretação dos eventos estão em:

* [`recorder.rs`](../../load-test/rust-loadtool/crates/loadtool-generator/src/recorder.rs);
* [`generation.rs`](../../load-test/rust-loadtool/crates/loadtool-report/src/generation.rs);
* [`summary.rs`](../../load-test/rust-loadtool/crates/loadtool-report/src/summary.rs).

Os contratos da preparação e da execução estão em [`prepare-performance-environment.sh`](../../load-test/prepare-performance-environment.sh) e [`run-load-test.sh`](../../load-test/run-load-test.sh).
