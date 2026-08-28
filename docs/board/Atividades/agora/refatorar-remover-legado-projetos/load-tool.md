# Auditoria de encerramento — Load-tool e automação de carga

## Estado

| Gate A — negócio | Gate B — técnica | estado | próxima ação |
| --- | --- | --- | --- |
| aprovado | aprovado | concluído | nenhuma; etapa encerrada |

## Contexto

O load-tool já passou por um cleanup estrutural amplo: a implementação Go foi removida, o gerador Rust foi isolado do relatório, preparação e execução foram separadas, a telemetria foi reduzida e contratos históricos foram apagados. Esta etapa não pretende repetir o cleanup. Ela audita o estado final, corrige somente resíduos comprovados e registra formalmente o encerramento conforme a metodologia vigente.

## Objetivo essencial proposto

Gerar workloads configurados de pagamentos originais em uma taxa temporal fiel, conduzir apenas as interações causais necessárias para exercitar o SPI e registrar evidências suficientes para avaliar throughput, outcomes externos e latência sem adicionar trabalho relevante ao sistema medido.

## Inventário funcional

| capacidade observável | contribuição ao objetivo | proposta |
| --- | --- | --- |
| validar um profile e produzir seu plano normalizado | rejeita workloads incoerentes antes de qualquer efeito e preserva a configuração executada | manter |
| preparar uma stack limpa, fundos e identidades mTLS para um profile | torna runs comparáveis e reproduzíveis sem misturar preparação com a janela medida | manter como automação externa ao gerador |
| oferecer pagamentos PACS.008 originais com pacing absoluto e sem catch-up | materializa a carga temporal que o experimento pretende provar | manter |
| exercitar happy-path e insufficient-funds com distribuição hot/cold configurada | cobre os outcomes de negócio e o skew aprovados para o workload do MVP | manter |
| consumir notificações como PSP recebedor e produzir o PACS.002 causal | fecha o fluxo externo necessário para o SPI processar o pagamento | manter |
| repetir PACS.008 e PACS.002 de forma determinística e como carga adicional | exercita replays idênticos sem substituir pagamentos originais | manter |
| observar notificações do pagador sob semântica at-least-once | prova ACSC ou RJCT/AM04 pelo fluxo externo sem consultar o banco do SPI | manter |
| registrar evidência individual com timestamps e gerar o relatório após a carga | permite reconstruir throughput rolling, latência, outcomes e replays sem agregar no hot path | manter |
| preservar profile, plano, logs e diagnósticos no bundle do run | torna a execução auditável e comparável | manter |
| coletar JFR, estatísticas PostgreSQL e recursos dos containers | permite investigar gargalos sem contaminar o gerador com responsabilidade de diagnóstico | manter como suporte externo e desativável |
| oferecer profiles de smoke, diagnóstico e qualificação | separa feedback funcional rápido, investigação e prova oficial de 15 minutos | manter |

## Fronteiras e ambiguidades funcionais

### Resultado do comando e qualificação

Uma execução tecnicamente concluída não falha apenas porque throughput, SLA ou outcome ficaram fora do esperado. O comando preserva os artefatos e o relatório mostra os desvios; exit code diferente de zero permanece reservado para falha operacional da ferramenta ou impossibilidade de concluir o experimento. Proposta: manter esta decisão já adotada.

### Profile oficial

Somente `mixed-outcomes-2k-15m` qualifica o contrato de 2.000 pagamentos originais por segundo durante 15 minutos. Smokes e profiles diagnósticos não substituem essa prova. Proposta: manter esta distinção já documentada.

### Escopo da validação externa

O load-tool valida HTTP e notificações PACS.002 observáveis, inclusive status e reason code sob at-least-once. Invariantes internas de saldo, settlement, auditoria, outbox e idempotência pertencem aos testes do SPI. Proposta: preservar essa fronteira e não reintroduzir consultas ao banco.

### Replays

Replays são carga adicional planejada e determinística. O relatório pode comparar obrigações selecionadas, tentativas e aceite HTTP em contagens agregadas, mas não deve reconstruir identidade byte a byte nem criar uma taxonomia pública de defeitos do gerador. Proposta: manter o contrato atual.

### Diagnósticos

Diagnósticos permanecem ativos por padrão nos benchmarks e podem ser desativados explicitamente. Eles pertencem à orquestração shell, não ao domínio nem ao hot path do gerador. Proposta: manter essa separação.

## Evidência histórica do cleanup

Entre outras mudanças, o histórico registra a remoção da implementação Go, isolamento do gerador Rust, execução somente de workloads preparados, redução de telemetria e do relatório, centralização da validação do plano e simplificação dos resultados do run. A auditoria técnica verificará apenas se restaram referências, responsabilidades duplicadas ou código sem consumidor no estado atual.

## Diagnóstico técnico

### Baseline

* os 86 testes do workspace Rust passaram;
* os 13 testes shell passaram;
* Clippy passou sem warnings;
* `bash -n` passou em runners, scripts e testes;
* `cargo fmt --check` encontrou apenas uma quebra de linha fora do formato padrão em um teste;
* nenhum `TODO`, `FIXME`, `HACK`, dependência sem uso ou referência ativa à implementação Go foi encontrado;
* outputs locais ignorados, como `target`, `results`, `.prepared-environment` e `__pycache__`, não estão versionados.

### Complexidade essencial preservada

* as crates `loadtool-generator`, `loadtool-report` e `loadtool-contract` mantêm a geração separada da interpretação pós-run;
* pacing nativo, preparação antecipada de buckets, HTTP/2 persistente, Pull, replays, gate de warmup, recorder single-writer e capacidades bounded implementam diretamente o workload aprovado;
* o bundle tipado impede sobrescrita e mantém evidência suficiente sem formatos históricos;
* scripts shell continuam donos da stack, provisionamento e diagnósticos; mover essas responsabilidades para Rust aumentaria acoplamento;
* a repetição explícita das conexões nos profiles mantém cada workload autocontido e não será substituída por herança ou configuração global;
* validação do profile editável e validação independente do plano normalizado protegem fronteiras diferentes e não são duplicação acidental.

### Resíduos encontrados

1. `serverName` possui `serde(default)` no compilador do profile, mas não recebe default nem é validado. Assim, `validate-profile` pode aceitar um snapshot que o comando `run` rejeita posteriormente.
2. `--no-postgres-statements` desativa também activity sampling, I/O, server log e estatísticas dos containers. O nome público e as variáveis internas descrevem apenas uma parte do comportamento.
3. `requests_in_bucket` e seu teste representam o pacer antigo de buckets de 1 ms; o pacer vigente usa buckets de 10 ms e já possui testes próprios da distribuição real.
4. `EventRecorder::record` não possui consumidor de produção; os testes usam uma conveniência que contorna o mesmo `EventSender` usado pelo runtime.
5. Existem asserts dedicados somente a provar que `run-window.json`, `generator-metrics.json`, `valid`, `performance_qualified` e flags antigas continuam ausentes.
6. A fixture `report-parity` conserva o nome da migração Go/Rust, embora hoje seja apenas a fixture canônica do contrato do relatório.
7. Dois testes unitários apenas repetem os valores privados de capacidade do canal e spin tail, enquanto os testes comportamentais já protegem pacing, expiração e ausência de carry-over.

Não foram encontradas filas escondidas, fallback histórico, código Go, responsabilidade de reporting no gerador ou nova oportunidade de performance com ROI evidente. As funções grandes restantes coordenam protocolos concorrentes aprovados; dividi-las novamente sem mudança de ownership apenas espalharia o fluxo.

## Intervenção mínima proposta para o Gate B

1. Tornar `serverName` obrigatório e não vazio na validação do profile, com teste de rejeição antecipada.
2. Renomear `--no-postgres-statements` para `--no-system-diagnostics`, incluindo variáveis e testes, sem manter alias legado. O comportamento permanece idêntico: a flag desativa diagnósticos PostgreSQL e estatísticas de containers, enquanto `--no-jfr` continua independente.
3. Remover `requests_in_bucket`, seu teste do scheduler antigo e `EventRecorder::record`; adaptar o teste do recorder para usar `EventSender` como a produção.
4. Remover asserts cuja única função é preservar a ausência de artefatos, campos e flags já apagados, além dos dois testes que apenas espelham constantes privadas.
5. Renomear `testdata/report-parity` e seu profile interno para `report-contract`.
6. Aplicar `cargo fmt` e atualizar somente o trecho vigente do README afetado pela flag.

Não haverá mudança de workload, profile, pacing, protocolo, bundle, relatório, diagnóstico coletado ou semântica de exit code.

## Validação prevista

* `cargo test --locked --workspace`;
* `cargo clippy --locked --workspace --all-targets -- -D warnings`;
* `cargo fmt --all -- --check`;
* todos os testes shell de `load-test/tests`;
* `bash -n` nos scripts;
* buscas por nomes e contratos removidos;
* `git diff --check`.

## Gate A

Objetivo, inventário e resoluções aprovados. Nenhuma alteração técnica será iniciada antes da aprovação explícita do Gate B.

## Gate B

Intervenção mínima aprovada. A implementação fica limitada aos resíduos enumerados neste documento.

## Resultado

* `serverName` passou a ser obrigatório e não vazio nos dois endpoints do profile, fazendo a validação falhar antes da preparação do ambiente;
* `--no-postgres-statements` foi substituída por `--no-system-diagnostics`, que descreve corretamente a desativação conjunta dos diagnósticos PostgreSQL e das estatísticas dos containers;
* o cálculo residual do pacer de 1 ms e a API sem consumidor de produção do recorder foram removidos;
* testes que apenas preservavam a ausência de contratos apagados ou espelhavam constantes privadas foram removidos;
* a fixture histórica `report-parity` foi renomeada para `report-contract`;
* nenhuma regra de workload, pacing, replay, outcome, relatório, bundle ou exit code foi alterada.

A suíte Rust passou de 86 para 83 testes porque três testes sem comportamento próprio foram removidos: dois espelhavam constantes privadas e um exercitava o cálculo já morto do pacer antigo de 1 ms.

## Evidências finais

* 83 testes Rust passaram com `cargo test --locked --workspace`;
* Clippy passou sem warnings com `cargo clippy --locked --workspace --all-targets -- -D warnings`;
* `cargo fmt --all -- --check` passou;
* os 13 testes shell em `load-test/tests` passaram;
* `bash -n` passou nos runners, scripts e testes shell;
* `git diff --check` passou;
* as buscas finais não encontraram referências ativas à flag antiga, à fixture histórica, ao helper do pacer de 1 ms ou aos contratos removidos.
