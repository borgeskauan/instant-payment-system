# Consolidar evidências finais de performance

## Objetivo

Extrair o conhecimento ainda útil dos experimentos, consolidá-lo na documentação permanente e preservar uma evidência final auditável da capacidade de 2.000 pagamentos originais por segundo, sem transformar resíduos diagnósticos em entregáveis do MVP.

## Estado atual

* [x] A meta foi atingida e repetida durante a estabilização.
* [x] Método, decisões, resultados e limitações estão consolidados no [relatório de estabilização](../../../../performance/2k-tps-stabilization.md).
* [x] Hipóteses, resultados negativos, migração de gargalos e limitações experimentais estão curados no [apêndice de achados experimentais](../../../../performance/experimental-findings.md).
* [x] Profile, plano e relatórios compactos dos runs originais estão preservados no [manifesto de 2026-08-27](../../../../performance/evidence/2026-08-27/manifest.md).
* [x] O corpus histórico consolidado possui aproximadamente `34 GiB`, `255` bundles e `192` relatórios finais; ele reúne os resultados que já estavam extraídos e os `162` bundles recuperados de `load-test/results-20260823.tar.zst`.
* [ ] Substituir como evidência final do estado atual os dois runs de 2026-08-27, executados antes do cleanup do core.
* [x] Auditar os experimentos diretamente pelos bundles e incorporar aos relatórios somente evidências, decisões ou resultados negativos ainda não documentados.
* [ ] Preparar e validar localmente os três bundles finais e a metadata histórica compacta.
* [ ] Enviar os arquivos ao Cloudflare R2 e validar a recuperação.

Nada em `load-test/results` deve ser apagado antes da validação completa da cópia recuperada do R2.

## Evidência final atual

O primeiro candidato é `load-test/results/compare-rust-15m-fixed/20260828_220502`. Ele qualificou o profile oficial contra o runtime atual, incluindo a correção do Gateway identificada pelo checksum preservado no bundle e posteriormente incorporada ao Git.

Será executada uma única repetição limpa na revisão final. Os dois runs somente serão promovidos juntos se usarem profile, plano, recursos, instrumentação e runtime equivalentes e ambos satisfizerem throughput, latência e outcomes.

Os runs de `rust-qualification-15m-clean` e `rust-qualification-15m-repeat-clean` continuam fazendo parte da história da estabilização, mas não serão apresentados como prova do código após o cleanup.

## Extração de conhecimento histórico

Antes de remover os resultados intermediários, comparar seu conteúdo com o [relatório de estabilização](../../../../performance/2k-tps-stabilization.md) e com a [comparação Go e Rust](../../../../architecture/load-tool-go-rust-comparison.md). O objetivo não é documentar todos os runs, mas impedir que uma conclusão útil exista somente dentro de um bundle local.

Para cada família relevante de experimentos, classificar temporariamente o conhecimento como:

* já documentado e suficientemente sustentado;
* conclusão única que deve ser incorporada ao relatório permanente;
* resultado redundante, inconclusivo ou sem valor durável para o MVP.

Procurar especialmente por resultados negativos, hipóteses descartadas, migração de gargalos, divergência entre microbenchmark e end-to-end, variância entre repetições e trade-offs de concorrência, batching, storage e pacing.

Um inventário gerado pode ser usado durante essa auditoria, mas é artefato temporário. Ele não será versionado nem mantido como documentação pública. Depois que as conclusões únicas forem incorporadas e revisadas, o inventário deve ser removido.

Ausência de metadata em bundles antigos deve permanecer explícita. Não reconstruir causalidade ou conclusão apenas pelo nome do run.

A auditoria usou os `255` bundles como fonte primária: `192` possuem relatório final e `63` são execuções incompletas. O archive `load-test/results-20260823.tar.zst` foi validado e extraído sem colisões, recuperando `162` bundles anteriores. Profile e execution plan agruparam workloads equivalentes; relatórios, métricas do gerador, CSVs, logs e perfis distinguiram resultado válido, falha operacional e experimento perturbado por instrumentação. Nome de tag e ordem do Git foram usados somente como apoio, nunca como prova isolada de causalidade.

Os experimentos foram agrupados em cinco famílias duráveis: evolução do core e do PostgreSQL; arquiteturas de delivery; redução de overhead e variância do gerador Go; evolução do pacer/ownership do gerador Rust; e exploração acima da meta em `4.000 TPS`. Smokes de cleanup e tentativas interrompidas permaneceram evidência de regressão ou falha operacional, não amostras de performance.

A comparação revelou lacunas reais na síntese anterior. Foram incorporados ao [apêndice de achados experimentais](../../../../performance/experimental-findings.md): a migração de gargalos entre ingresso, PostgreSQL, delivery e gerador; a variância histórica do Go; a diferença entre ganho local e sistêmico; batching observado em vez de configurado; trade-offs físicos do schema; warmup e isolamento; o limite exploratório de `4.000 TPS`; a sequência de tentativas negativas do pacer Rust; o planner compartilhado como mudança decisiva; o custo da instrumentação intrusiva; e o fato de que o Pull vigente normalmente devolveu lotes de `1–3`, embora o teto do protocolo seja `15`.

Não foi criado um catálogo público de todos os runs. O caderno de estabilização continua preservando a cronologia detalhada, enquanto os documentos permanentes mantêm somente conclusões que sobrevivem ao descarte dos artefatos brutos. Bundles incompletos não foram promovidos a evidência de capacidade, mas suas falhas foram usadas quando distinguiam uma hipótese, como canal maior, pinning, spin prolongado, stack reutilizada e corrida de Pull.

O archive recuperou o bundle `pacs008-fetch-min-56k/20260823_142558`, permitindo reauditar a redução de `128` para `56 KiB`: o lote mediano permaneceu em `165`, enquanto p99/máximo do lote caíram de `281/493` para `235/350`, p99 do callback caiu de `105,511` para `72,191 ms` e p99 end-to-end caiu de `566,941` para `386,178 ms`. Como os dois diagnósticos ficaram abaixo do piso rolling e não constituem um A/B contrabalançado, a conclusão permanente foi limitada a menor espera e cauda observadas, sem atribuir ganho qualificador isolado.

Dos bundles citados pela documentação permanente, somente `baseline-buckets/20260814_023552` continua ausente. Há outro baseline da mesma geração em `baseline-buckets/20260814_093850`, mas ele não foi tratado como substituto nem usado para reconstruir metadata do run perdido.

## Evidência curada

Preservar como bundles brutos somente:

* o Go do A/B final: `load-test/results/compare-go-15m-fixed/20260828_214501`;
* o Rust do A/B final e primeiro candidato qualificador: `load-test/results/compare-rust-15m-fixed/20260828_220502`;
* a nova repetição limpa do Rust na revisão final.

Para os demais runs, preservar apenas profile, plano de execução, relatório final e manifesto existente. Runs incompletos continuam visíveis durante a auditoria temporária, mas não justificam preservar automaticamente seus CSVs, JFRs e logs.

Preparar localmente:

```text
performance/final/go-comparison-<date>.tar.zst
performance/final/rust-qualification-<date>.tar.zst
performance/final/rust-confirmation-<date>.tar.zst
performance/final/SHA256SUMS
performance/history/performance-history-compact-<date>.tar.zst
performance/history/SHA256SUMS
```

O pacote histórico compacto preserva somente os JSONs necessários para interpretar a evolução e permitir consulta futura. O histórico bruto completo de aproximadamente `34 GiB`, além do archive original de `1,6 GiB`, não é uma evidência obrigatória do MVP e não será enviado ao R2.

Antes da compactação, excluir certificados efêmeros, credenciais, tokens e qualquer outro segredo. A exclusão deve ser documentada; artefatos de diagnóstico não sensíveis permanecem.

## Cloudflare R2

R2 é a última etapa e será executada manualmente pelo usuário depois que todos os arquivos, checksums e instruções estiverem prontos.

* usar bucket privado e storage class Standard;
* não versionar credenciais nem tokens do R2;
* fazer upload multipart por cliente compatível com S3, preferencialmente `rclone`;
* registrar bucket, object key, tamanho e SHA-256 no manifesto, sem tornar os objetos públicos;
* baixar novamente cada objeto para outro diretório, validar SHA-256 e testar a extração;
* não tratar ETag multipart como checksum do arquivo;
* somente considerar a preservação concluída depois da recuperação validada.

## Ordem de execução

1. Usar um inventário temporário para auditar a cobertura dos experimentos sem alterar os bundles.
2. Incorporar aos relatórios permanentes somente conclusões únicas e evidências relevantes ainda ausentes.
3. Revisar a narrativa consolidada e remover o inventário temporário da documentação final.
4. Validar o candidato atual e executar uma repetição qualificadora limpa.
5. Selecionar formalmente os dois bundles qualificadores finais.
6. Extrair a metadata histórica compacta e auditar os três bundles brutos para conteúdo sensível.
7. Produzir os quatro arquivos `tar.zst` e seus `SHA256SUMS` localmente.
8. Testar integridade e extração local dos arquivos.
9. Entregar ao usuário os arquivos e comandos de upload ao R2.
10. Depois do upload, baixar, conferir checksums e testar recuperação.
11. Atualizar relatório e manifesto com a localização externa definitiva.
12. Solicitar autorização explícita antes de remover resultados locais.
13. Mover esta task para `concluidas`.

## Critérios de conclusão

* A afirmação final de capacidade referencia duas execuções comparáveis do runtime final.
* Os relatórios permanentes preservam as conclusões relevantes dos experimentos sem expor um catálogo de resíduos intermediários.
* O Git contém profile, plano, relatórios finais e manifesto da evidência selecionada.
* Os três bundles finais e a metadata histórica compacta existem no R2 privado com SHA-256 validado após download.
* As instruções permitem localizar, recuperar, verificar e interpretar os artefatos.
* CSVs, JFRs e logs volumosos não entram no Git comum.
* Nenhum resultado local é removido antes da recuperação externa validada e da autorização do usuário.

## Fora de escopo

* Criar visualizações novas ou reprocessar relatórios históricos.
* Inventar metadata ausente nos runs antigos.
* Fazer tuning, alterar SLAs ou redefinir o workload.
* Integrar R2 ao código da aplicação ou automatizar credenciais no repositório.
* Preservar no R2 todos os CSVs, JFRs e logs intermediários ou o diretório bruto completo de `34 GiB`.
