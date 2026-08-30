# Evidência final de performance

## Afirmação sustentada

O runtime do commit `1351ea564d0834a66e1b5d99a5e09a1a384cae1b` sustentou pelo menos `2.000` pagamentos originais por segundo durante toda a janela ativa de `15 minutos`, com p99 end-to-end abaixo do threshold interno de `1 segundo` e corretude funcional integral, em duas execuções consecutivas.

As duas runs usaram o profile `mixed-outcomes-2k-15m`, o mesmo plano normalizado, worktree limpa e ambientes novos criados pelo preparador oficial. Não houve mudança de código, configuração ou procedimento entre elas.

## Campanha qualificadora

| run | início local | originais planejados / executados | mínimo rolling | p99 | corretude |
| --- | --- | ---: | ---: | ---: | --- |
| A | `2026-08-29 19:29:40 -03:00` | `1.890.000 / 1.889.369` | `2.017 TPS` | `855,202 ms` | zero outcomes ausentes ou contraditórios e zero violações de replay |
| B | `2026-08-29 19:49:50 -03:00` | `1.890.000 / 1.890.000` | `2.079 TPS` | `265,195 ms` | zero outcomes ausentes ou contraditórios e zero violações de replay |

O total executado não é usado para compensar uma queda temporal. A aprovação exige que toda janela contínua de um segundo integralmente contida no active alcance o piso de `2.000 TPS`. As duas runs satisfizeram esse critério e o threshold de latência de forma independente.

A run A foi preservada deliberadamente apesar da cauda maior. Excluí-la e promover somente a amostra mais favorável reduziria a credibilidade da campanha. Sua aprovação demonstra que o piso e o SLA foram mantidos também na condição menos favorável observada; a run B confirma que o resultado é repetível. Em contrapartida, a campanha não sustenta `265 ms` como latência típica nem permite ignorar a variação: o p99 observado entre as duas execuções foi de `265,195` a `855,202 ms`, e o menor headroom rolling foi de apenas `17 TPS` sobre o piso.

## Artefatos qualificadores

* [`profile.json`](profile.json): profile comum às duas runs;
* [`execution-plan.json`](execution-plan.json): plano normalizado comum;
* [`qualification-run-a-sla-report.json`](qualification-run-a-sla-report.json): relatório da run A;
* [`qualification-run-b-sla-report.json`](qualification-run-b-sla-report.json): relatório da run B;
* [`checksums.sha256`](checksums.sha256): checksums dos artefatos compactos deste diretório.

CSVs, JFRs, logs, certificados e credenciais não integram a evidência canônica. Os relatórios qualificadores e os inputs versionados são suficientes para verificar a afirmação promovida sem depender de `load-test/results/**` local.

## Estudo Go/Rust separado

[`go-comparison-sla-report.json`](go-comparison-sla-report.json) e [`rust-comparison-sla-report.json`](rust-comparison-sla-report.json) pertencem ao estudo controlado entre os dois geradores. Eles sustentam a [comparação Go/Rust](../../../architecture/load-tool-go-rust-comparison.md), não a qualificação final de capacidade acima.

Essa separação é deliberada: o A/B de implementações responde qual gerador preserva melhor a workload; a campanha A/B do commit `1351ea5` responde se o runtime final qualifica repetidamente.
