# Evidência compacta da qualificação de 2k TPS

## Execuções

| execução | início local | diretório de resultado bruto | tamanho aproximado |
| --- | --- | --- | ---: |
| qualificação | `2026-08-27 03:07:42 -03:00` | `load-test/results/rust-qualification-15m-clean/20260827_030742` | `1,1 GiB` |
| repetição | `2026-08-27 03:36:39 -03:00` | `load-test/results/rust-qualification-15m-repeat-clean/20260827_033639` | `1,1 GiB` |

As duas execuções usaram profile e plano de execução byte a byte idênticos. O
HEAD reconstruído pelo histórico local é `a08fdd5`; os dois commits anteriores
a ele alteravam somente documentação, e a última mudança no runtime era
`975f72f`. Essa revisão não foi persistida nos artefatos da execução e deve ser tratada como
evidência reconstruída, não como metadata autoritativa do artefato.

## Arquivos compactos preservados

| arquivo | SHA-256 original |
| --- | --- |
| [`profile.json`](profile.json) | `721561af3f241a25de2d5124d9625eb06543f5ad8aa7f31072a72dbe877e104a` |
| [`execution-plan.json`](execution-plan.json) | `ebef7b929c37f50f41ed5cdba027f483824038ac8bb40991e0267167bdf94508` |
| [`run-1-sla-report.json`](run-1-sla-report.json) | `f324ff4165b2163aeef347479807517449b72a8fe211926f1f8a3e33f460e920` |
| [`run-2-sla-report.json`](run-2-sla-report.json) | `7af0057e2b9417b8b5e587fc919603fabf555a29942e4997bfe8703ae1a16b73` |

## Checksums de artefatos brutos

| execução | artefato | SHA-256 |
| --- | --- | --- |
| qualificação | `diagnostics/container-stats.csv` | `4a90a5b7dac643d6ee2c7efef3ec9b845ebfda867e9862901fd63ff30f887886` |
| qualificação | `events/pacs008-starts.csv` | `ba7d48146c338adb3fff0b03e6289d06de8da889386b7203129a98a3e69fbe66` |
| qualificação | `events/notifications.csv` | `26bbc2b59a229e49229648b051be33ed6f1da2c1cf3fb69e0c0167cb096ec1a0` |
| repetição | `diagnostics/container-stats.csv` | `9c66d6f5ee8b7e070fdd9490ee84c4d30b293d97df78385ea0f334e79d0f6e92` |
| repetição | `events/pacs008-starts.csv` | `ee0b8b13595699839b7488679a9ebb29439603431b593d9db6bac19ee3226aed` |
| repetição | `events/notifications.csv` | `279f602af30c4bc1bb0b95767885320a6bbb632255d98fb3f0b27a594e9620e5` |

## Limitações de preservação

Os diretórios de resultado brutos continuam somente no armazenamento local e ainda não possuem
um arquivo externo durável com checksum do resultado completo. Não remover os dois
diretórios antes de concluir essa etapa da task de consolidação. Certificados
efêmeros, credenciais, CSVs grandes, JFRs e logs não são versionados neste
diretório.
