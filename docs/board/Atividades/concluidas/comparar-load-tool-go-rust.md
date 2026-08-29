# Comparar as implementações Go e Rust do load-tool

* [x] Produzir um relatório técnico comparando a implementação Go descontinuada com a implementação final em Rust

## Estado

Concluído em 28 de agosto de 2026.

## Objetivo

Registrar por que o load-tool foi refeito em Rust e avaliar, com evidências, o resultado da mudança em performance e previsibilidade, simplicidade arquitetural e manutenção.

## Resultado

O relatório final está em [`../../../architecture/load-tool-go-rust-comparison.md`](../../../architecture/load-tool-go-rust-comparison.md).

No A/B principal, ambas as implementações executaram o mesmo profile `mixed-outcomes-2k-15m`, contra a mesma revisão e o mesmo patch do Gateway, após recriação completa da stack e dos volumes. Os dois lados preservaram HTTP 2xx, outcomes esperados e ausência de contradições.

Rust executou 1.889.945 de 1.890.000 originais ativos e sustentou rolling mínimo de 2.058 TPS. Go executou 1.883.094 e atingiu rolling mínimo de 1.784 TPS. Rust consumiu 34,1% menos CPU-time e teve RSS máximo 27,2% menor no processo completo de geração e relatório.

A conclusão não reduz a comparação a desempenho: Go permaneceu menor em linhas, arquivos e dependências; Rust introduziu uma superfície maior de crates e async, mas estabeleceu fronteiras físicas entre geração, contratos e relatório e tornou o pacing temporalmente mais previsível.

## Evidências principais

```text
load-test/results/compare-go-15m-fixed/20260828_214501
load-test/results/compare-rust-15m-fixed/20260828_220502
```

Os manifestos registram profile, revisão do core, digest do patch, revisão do gerador, digest do binário e horário de início. Os bundles preservam o patch exato aplicado ao Gateway durante a campanha.

## Ocorrência durante a campanha

Duas tentativas Rust iniciais revelaram uma corrida no lifecycle do Pull do Notification Gateway: o cliente podia observar `onCompleted()` antes de a sessão ser removida e o Pull sequencial seguinte era rejeitado como concorrente. A correção mínima libera a sessão antes de publicar a conclusão, possui teste de regressão e passou nos 41 testes do Gateway. Como o core mudou, Go e Rust foram ambos reexecutados contra a mesma correção.

## Validação

* smoke Go e Rust em stacks novas;
* `go test ./...` na última revisão relevante do load-tool Go;
* `cargo test --locked --workspace` no load-tool Rust atual;
* 41 testes do Notification Gateway;
* uma run operacionalmente completa de 15 minutos por implementação após a correção comum;
* reconstrução comum do rolling throughput e da distribuição de inícios em janelas de 10 ms;
* inventário reproduzível de arquivos, linhas, dependências, testes e tamanho dos binários;
* revisão explícita das limitações e das afirmações que não podem ser atribuídas somente à linguagem.

## Decisão

Manter apenas a implementação Rust. Ela é a única das duas que comprovou o piso sustentado no ambiente exigido pelo projeto. A implementação Go continua reconhecida como suficiente para smokes funcionais, diagnósticos de menor taxa ou execução com recursos isolados, mas não para qualificar o contrato atual no host compartilhado.
