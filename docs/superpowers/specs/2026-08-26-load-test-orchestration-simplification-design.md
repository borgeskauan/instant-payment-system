# Simplificação da orquestração do load test

## Propósito

Reduzir o load tooling ao trabalho necessário para preparar uma execução,
gerar a workload e preservar suas evidências. A mudança separa definitivamente
três fronteiras:

```text
preparador
  deixa uma stack limpa e pronta para um profile

runner
  delimita uma execução e sua instrumentação

Rust load-tool
  gera a workload, registra fatos e produz o relatório
```

O princípio orientador continua sendo simplicidade por adesão ao objetivo:

> O gerador produz PACS.008 originais dentro do envelope temporal configurado,
> executa apenas as continuações causais da workload e registra fatos para
> validação posterior.

Esta spec preserva as fronteiras entre os crates Rust definidas em
`2026-08-26-rust-load-tool-boundaries-design.md`, mas substitui sua antiga
distribuição de responsabilidades de ambiente, bundle e exit code. Também
substitui os desenhos anteriores nos quais o runner preparava ou provisionava o
ambiente de cada run.

## Princípios

Em ordem de prioridade:

1. simplicidade por aderência ao objetivo do load test;
2. manutenção;
3. performance e previsibilidade do gerador.

Não serão mantidos mecanismos somente porque formatos ou requisitos anteriores
um dia existiram. O desenho não oferece rerun histórico do report, migração de
bundles antigos nem compatibilidade com `run-window.json`.

## Fluxos públicos

### Execução candidata à qualificação

Uma execução candidata a comprovar performance sempre começa com preparação
nova:

```text
./prepare-performance-environment.sh --profile NAME
./run-load-test.sh --profile NAME RUN_TAG
```

Os dois comandos continuam usando `uniform-smoke` quando `--profile` é
omitido, preservando a interface existente. A documentação do fluxo qualificado
usa o nome explícito para deixar evidente qual ambiente está sendo preparado e
executado.

O preparador remove a stack e os volumes anteriores. Seu sucesso significa que
build/start, readiness, provisionamento e certificados do profile terminaram.
O runner não tenta reparar uma preparação ausente ou incompleta.

### Execuções exploratórias

Depois da preparação, o runner pode ser chamado mais de uma vez:

```text
./run-load-test.sh --profile NAME experiment-a
./run-load-test.sh --profile NAME experiment-b
```

A primeira run depois de uma preparação limpa é candidata à qualificação. As
demais são exploratórias, pois podem herdar aquecimento, saldos e trabalho
residual. Essa classificação é uma convenção operacional explícita, não uma
máquina de estados implementada pela ferramenta. Não haverá token, marker de
consumo ou metadado que tente fiscalizar o operador.

Quando o estado reutilizado deixar de servir ao experimento, o operador executa
o preparador novamente.

## Estado preparado

O preparador publica um único ambiente ativo:

```text
load-test/.prepared-environment/<profile>/
├── inputs/
│   ├── profile.json
│   └── execution-plan.json
└── certs/
```

`.prepared-environment/` é ignorado pelo Git.

O nome do diretório identifica o profile preparado. `profile.json` continua
sendo a fonte declarativa e `execution-plan.json` continua sendo o resultado
normalizado e reprodutível da validação Rust. Nenhum manifesto adicional repete
o nome do profile.

### Publicação segura

O preparador invalida o estado preparado anterior antes de modificar a stack e
trabalha em um diretório de staging específico. A ordem é:

1. validar o nome e resolver o profile interno;
2. compilar o Rust load-tool em release usando o target compartilhado;
3. validar o profile, copiar seu snapshot e gerar o execution plan;
4. remover a stack e seus volumes;
5. construir e iniciar a stack;
6. aguardar readiness;
7. provisionar participantes a partir do plano validado;
8. gerar os certificados dos PSPs descritos no plano;
9. renomear atomicamente o staging para `.prepared-environment`.

Falha em qualquer etapa remove o staging e não deixa um estado anterior
aparentemente válido. O preparador não inicia workload nem diagnósticos da run.

O código que consome o execution plan para funding e certificados somente
extrai campos já validados pelo Rust. Ele não reimplementa semântica de
cenários, limites, ranges ou expectativas em Python ou Bash.

## Responsabilidade do runner

O runner passa a executar apenas:

```text
parse args
  → localizar .prepared-environment/<profile>
  → compilar o Rust load-tool em release usando cache
  → criar o result-dir e copiar os inputs preparados
  → executar o load-tool sob a fronteira de diagnósticos
  → preservar o exit code e o bundle
```

Ele não:

- remove volumes ou sobe containers;
- executa readiness;
- provisiona saldos;
- gera certificados;
- faz validação superficial de profile em Python;
- converte o execution plan em variáveis e arrays Bash;
- reproduz a configuração inteira em logs próprios;
- interpreta `sla-report.json`;
- cria uma cópia temporária do binário Rust;
- enriquece artefatos gerados pelo Rust.

O binário é usado diretamente a partir do target Cargo compartilhado. O cache
permite recompilar alterações entre experimentos sem obrigar a recriação da
stack.

O profile continua contendo endpoints, CA e server name. O runner fornece ao
Rust somente o diretório de certificados PSP preparado. Os overrides separados
de CA, client root e server name para Central Transfer e Notification Gateway
são removidos da CLI interna, pois não há caso atual que exija valores
divergentes.

## Fronteira de diagnósticos

Detalhes de instrumentação saem do corpo do runner e ficam atrás de uma
operação própria:

```text
scripts/run-diagnostics.sh
  run --run-dir DIR [--no-jfr ...] -- COMMAND
```

O wrapper é responsável por:

- iniciar JFR do SPI, Kafka Producer e Notification Gateway;
- iniciar SPI trace;
- habilitar e zerar `pg_stat_statements`;
- iniciar amostragem de atividade e recursos dos containers e capturar os
  snapshots de I/O;
- delimitar a captura de logs PostgreSQL;
- executar o comando recebido;
- sempre tentar parar e coletar os diagnósticos;
- preservar os arquivos produzidos no sucesso e na falha.

O runner conhece apenas essa operação. Ele não conhece containers, PIDs,
recordings, queries de diagnóstico nem o lifecycle dos samplers. O Rust
load-tool também permanece desacoplado de todos esses detalhes.

As flags `--no-jfr`, `--no-spi-trace` e `--no-postgres-statements` permanecem
no runner e são encaminhadas ao wrapper. A instrumentação continua ativa por
padrão.

## Exit codes e falhas

O Rust load-tool passa a ser a única autoridade sobre o resultado funcional:

```text
0  execução concluída e sla-report.json válido
1  execução concluída, mas workload ou SLA inválido
2  falha operacional normal da ferramenta
```

Uma execução concluída escreve `sla-report.json` antes de retornar `0` ou `1`.
Falha operacional anterior à conclusão pode não produzir relatório, mas nunca
apaga evidências e logs já gravados.

O wrapper de diagnósticos preserva qualquer status não zero do comando. Uma
falha de coleta não substitui uma falha original do Rust; ambas ficam nos logs.
Se o Rust retornar sucesso e a instrumentação falhar, o wrapper retorna `2`.
Se a instrumentação não puder ser iniciada, a workload não começa.

O runner não abre o relatório para decidir o status público. Isso remove a
segunda implementação do contrato em Python.

## Bundle corrente

Cada run produz somente o contrato atual:

```text
results/<tag>/<timestamp>/
├── inputs/
│   ├── profile.json
│   └── execution-plan.json
├── events/
│   ├── pacs008-starts.csv
│   ├── pacs002-starts.csv
│   ├── notifications.csv
│   └── replays.csv
├── diagnostics/
│   └── loadtool/generator-metrics.json
├── logs/
└── sla-report.json
```

`profile.json` e `execution-plan.json` são cópias byte a byte do estado
preparado. Certificados não fazem parte do resultado.

`run-window.json` e todo seu módulo de contrato são removidos. A janela
autoritativa passa a fazer parte de `generator-metrics.json`:

```json
{
  "window": {
    "generationStartedAtNs": 0,
    "activeStartedAtNs": 0,
    "generationEndedAtNs": 0,
    "replayDeadlineAtNs": 0
  }
}
```

Os valores são timestamps Unix em nanossegundos projetados a partir da origem
monotônica do gerador, na mesma base temporal dos CSVs. O report valida:

- `activeStartedAtNs` não antecede o fim planejado do warmup;
- `generationEndedAtNs = activeStartedAtNs + activeDuration`;
- `replayDeadlineAtNs = generationEndedAtNs + drain`;
- todas as fronteiras são ordenadas e representáveis.

Não existe `warmupEndedAt`: o fim planejado é derivado do execution plan e o
início efetivo do active já está registrado.

## Telemetria reduzida do gerador

`generator-metrics.json` mantém somente sinais necessários para qualificar o
próprio gerador:

- validade e violações do gerador;
- slots planejados, iniciados, concluídos e perdidos;
- motivos de misses do pacer e da admissão semântica;
- histograma de lateness do pacer;
- histograma de lateness do início HTTP;
- violações de capacidade;
- máximo de HTTP causal in-flight;
- CPU e RSS máximo do processo;
- distribuição dos batches de Pull;
- janela autoritativa da execução.

São removidos do contrato e do hot path:

- slots `dispatched` como métrica persistida intermediária;
- decomposição de deadline entre entrada, retorno do sleep e fim do spin;
- lateness isolada do retorno do sleep;
- dispatch lateness intermediária;
- tempo total de spin;
- in-flight separado de PACS.008 original e replay;
- valor atual de in-flight causal ao final;
- campos derivados que apenas repetem a soma de outros contadores.

Essa poda remove atomics e observações introduzidas para diagnósticos pontuais
já encerrados. Nenhum dado individual necessário para auditoria, gráficos ou
reconstrução do report é removido dos CSVs.

## Simplificação interna do Rust

As fronteiras de crates permanecem:

```text
rust-loadtool CLI
├── loadtool-generator
├── loadtool-report
└── loadtool-contract
```

Não pode existir dependência de `loadtool-generator` para `loadtool-report`.
O bundle em disco continua sendo o único handoff entre geração e reporting.

O `simulator.rs` deixa de concentrar lifecycle, HTTP e tratamento de
notificações. A divisão lógica alvo é:

```text
simulator.rs     composição da execução
lifecycle.rs     setup, warmup, active, drain e shutdown
original.rs      preparação, admissão e envio de PACS.008
notification.rs  Pull, outcomes, PACS.002 e replays causais
runtime.rs       estado compartilhado mínimo, cancelamento e TaskTracker
```

A divisão deve estreitar dependências; não basta mover blocos entre arquivos.
Não será criado framework de cenários, actor system, worker pool fixo ou camada
genérica de eventos.

Simplificações locais permitidas nesta passagem:

- substituir estado escrito uma vez, como `ActiveWindow`, por primitiva de
  inicialização única;
- resolver participantes uma vez para índices densos;
- manter cabeçalhos CSV junto das respectivas representações de eventos;
- compartilhar uma única implementação de escrita JSON atômica;
- calcular valores derivados do profile durante a compilação do execution plan;
- remover locks, campos e parâmetros que perderem todos os consumidores.

O comportamento de pacing, workload, Pull, PACS.002, replay, warmup gate e drain
permanece inalterado. Não entram encoding JSON manual, buffer pool, sharding,
padding, schedulers próprios ou outras otimizações sem benchmark.

## Testes

### Preparador

Testes shell com adaptadores falsos devem provar:

- profile explícito é resolvido e validado pelo Rust;
- profile omitido resolve `uniform-smoke`;
- funding e certificados consomem o plano normalizado;
- stack, readiness, funding e certificados acontecem na ordem definida;
- falha em qualquer etapa não publica `.prepared-environment`;
- preparação bem-sucedida publica profile, plano e certificados;
- nova preparação invalida o estado anterior.

### Runner e diagnósticos

Testes shell devem provar:

- profile não preparado ou diferente falha antes da workload;
- runner não remove volumes, não sobe stack, não provisiona e não gera certs;
- inputs do resultado são byte-idênticos aos preparados;
- duas runs podem reutilizar o mesmo ambiente;
- diagnósticos são encerrados e coletados no sucesso e na falha;
- status `0`, `1` e `2` do Rust são preservados;
- falha exclusiva de diagnóstico retorna `2`;
- falha do Rust não é substituída por falha posterior da coleta.

### Rust

Testes devem provar:

- o bundle concluído não depende de `run-window.json`;
- o report usa exclusivamente a janela de generator metrics;
- fronteiras incompatíveis com o execution plan são rejeitadas;
- os três resultados normais da CLI mapeiam para os exit codes públicos;
- o contrato reduzido de generator metrics é escrito e consumido;
- os fluxos PACS.008, Pull, PACS.002, replay e warmup preservam seus testes de
  comportamento após a divisão interna.

Não serão criados testes de rejeição para formatos históricos que deixaram de
ser parte da aplicação.

### Verificação final

```text
cargo fmt --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
bash -n nos scripts alterados
suíte shell do load test
git diff --check
```

Depois da suíte automatizada:

1. preparar `uniform-smoke`;
2. executar uma run curta candidata à qualificação;
3. executar uma segunda run exploratória sem nova preparação;
4. confirmar zero misses e registrar CPU/RSS para comparação informativa.

Esta simplificação não cria um novo gate numérico de CPU/RSS. Uma regressão de
corretude temporal, como misses novos, bloqueia a mudança; oscilações de recurso
sem impacto funcional são apenas evidência para análise.

Uma run de 15 minutos não é necessária para validar esta simplificação.

## Documentação

O README apresenta em local evidente:

```text
preparar novamente → execução candidata à qualificação
reutilizar preparação → execução exploratória
```

A task de separação entre preparador e runner é atualizada para refletir o
estado final. Mensagens e nomes que ainda sugiram duas preparações diferentes
são removidos ou renomeados. O adaptador interno atual
`prepare-environment.sh` passa a ter nome que descreva especificamente o
provisionamento que realiza: `provision-profile-funds.sh`.

## Fora de escopo

- alterar profiles, workloads, cenários ou expectativas de negócio;
- alterar pacing, buckets, deadlines, warmup gate ou fixed drain;
- alterar protocolo HTTP/2, Pull ou mTLS;
- mover a configuração de connections para fora do profile;
- adicionar heurística de quiescência interna;
- marcar automaticamente runs como qualificadas ou exploratórias;
- suportar rerun histórico do report ou bundles antigos;
- portar Docker, funding, certificados ou diagnósticos para Rust;
- fazer tuning de SPI, Kafka, PostgreSQL ou Notification Gateway;
- executar nova campanha de 15 minutos;
- adicionar visualização ou nova camada de relatório.

## Resultado esperado

O sistema termina com um preparador integral para cada profile, um runner curto
que apenas delimita a execução e um Rust load-tool que possui sozinho o
contrato funcional do resultado. A redução remove validação duplicada, parsing
Python do report, artefatos redundantes e instrumentação de investigação do hot
path, sem eliminar runs exploratórias nem as evidências individuais necessárias
para analisar cada pagamento.
