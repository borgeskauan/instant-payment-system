# Evidência final de performance

## Escopo

Este diretório preserva os artefatos compactos do A/B entre os geradores Go e Rust e das duas execuções Rust que sustentam a qualificação final de `2.000 TPS`. Os três runs usaram o profile `mixed-outcomes-2k-15m` e profile e plano de execução byte a byte idênticos.

## Execuções selecionadas

| finalidade | início local | relatório versionado | resultado |
| --- | --- | --- | --- |
| comparação Go | `2026-08-28 21:45:01 -03:00` | [`go-comparison-sla-report.json`](go-comparison-sla-report.json) | não atingiu o piso rolling: `1.784 TPS`; p99 `513,530 ms`; corretude preservada |
| primeira qualificação Rust | `2026-08-28 22:05:02 -03:00` | [`rust-qualification-sla-report.json`](rust-qualification-sla-report.json) | rolling mínimo `2.058 TPS`; p99 `409,163 ms`; corretude preservada |
| confirmação Rust | `2026-08-29 01:05:34 -03:00` | [`rust-confirmation-sla-report.json`](rust-confirmation-sla-report.json) | rolling mínimo `2.058 TPS`; p99 `329,152 ms`; corretude preservada |

As duas execuções Rust partiram de stack e volumes novos. Elas não apresentaram outcomes ausentes ou contraditórios nem violações de replay. A confirmação executou `1.889.979` dos `1.890.000` originais planejados; a regra qualificadora é o piso rolling observado, não igualdade entre quantidade planejada e executada.

Uma [primeira tentativa de confirmação](rust-nonqualifying-attempt-sla-report.json) preservou corretude e p99 de `758,056 ms`, mas não foi promovida porque o rolling mínimo ficou em `1.995 TPS`.

## Origem do código

Os manifests do A/B registram a revisão base `c68d74de28d65b5a223d2af42156d356431c6d87` e o patch do lifecycle do Gateway com SHA-256 `fb94bea3736c532569ed130bc29de4993e71a2f1e4a6f139d1c222ede0eb75cc`. O gerador Go veio da revisão `1d80cedf00e5905b24c515cd7d5dc12d2207cc22`; o Rust, da revisão base do core. O patch usado nos dois runs do A/B foi posteriormente incorporado ao repositório.

A confirmação Rust foi iniciada com worktree limpa no commit `35c9bfa2408e9676b460fb8cc5ff5ff65cfc4f7d`. Entre o A/B e a confirmação, o runtime do core/load-tool não mudou além da incorporação do mesmo patch já presente nos binários do A/B; as demais mudanças foram de demo e documentação.

## Arquivos preservados

| arquivo | SHA-256 |
| --- | --- |
| [`profile.json`](profile.json) | `721561af3f241a25de2d5124d9625eb06543f5ad8aa7f31072a72dbe877e104a` |
| [`execution-plan.json`](execution-plan.json) | `ebef7b929c37f50f41ed5cdba027f483824038ac8bb40991e0267167bdf94508` |
| [`go-comparison-sla-report.json`](go-comparison-sla-report.json) | `af7f62bd3e7c143c9826b42ee2224139635c0482815a3f236d60d011d1314e63` |
| [`rust-qualification-sla-report.json`](rust-qualification-sla-report.json) | `58a0749aa229664c6194d20183c099cb1549588fe82b9b287f832280bccb6418` |
| [`rust-confirmation-sla-report.json`](rust-confirmation-sla-report.json) | `0ec01d21a91ac0e12e880f227cf874a02d72b064bb328aa1595a1d59c746a52a` |
| [`rust-nonqualifying-attempt-sla-report.json`](rust-nonqualifying-attempt-sla-report.json) | `d15c3f36bcfe49cc6149a63cb059bf0ab7bcfe35363dabbfe03b60e743195f82` |

Os checksums são idênticos aos arquivos de origem nos bundles. CSVs, JFRs, logs, diagnósticos volumosos, certificados e credenciais não são versionados como evidência compacta.
