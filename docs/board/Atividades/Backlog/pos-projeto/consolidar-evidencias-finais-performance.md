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
* [ ] Versionar no Git os artefatos compactos dos três runs finais e atualizar o manifesto.

## Evidência final atual

O primeiro candidato é `load-test/results/compare-rust-15m-fixed/20260828_220502`. Ele qualificou o profile oficial contra o runtime atual, incluindo a correção do Gateway identificada pelo checksum preservado no bundle e posteriormente incorporada ao Git.

Será executada uma única repetição limpa na revisão final. Os dois runs somente serão promovidos juntos se usarem profile, plano, recursos, instrumentação e runtime equivalentes e ambos satisfizerem throughput, latência e outcomes.

Os runs de `rust-qualification-15m-clean` e `rust-qualification-15m-repeat-clean` continuam fazendo parte da história da estabilização, mas não serão apresentados como prova do código após o cleanup.

## Extração de conhecimento histórico

A auditoria comparou os resultados intermediários com o [relatório de estabilização](../../../../performance/2k-tps-stabilization.md) e com a [comparação Go e Rust](../../../../architecture/load-tool-go-rust-comparison.md). O objetivo não foi documentar todos os runs, mas impedir que uma conclusão útil existisse somente dentro de um bundle local.

Para cada família relevante de experimentos, classificar temporariamente o conhecimento como:

* já documentado e suficientemente sustentado;
* conclusão única que deve ser incorporada ao relatório permanente;
* resultado redundante, inconclusivo ou sem valor durável para o MVP.

Procurar especialmente por resultados negativos, hipóteses descartadas, migração de gargalos, divergência entre microbenchmark e end-to-end, variância entre repetições e trade-offs de concorrência, batching, storage e pacing.

Um inventário gerado pode ser usado durante essa auditoria, mas é artefato temporário e não integra a documentação pública.

Ausência de metadata em bundles antigos deve permanecer explícita. Não reconstruir causalidade ou conclusão apenas pelo nome do run.

A auditoria usou os `255` bundles como fonte primária: `192` possuem relatório final e `63` são execuções incompletas. O archive `load-test/results-20260823.tar.zst` foi validado e extraído sem colisões, recuperando `162` bundles anteriores. Profile e execution plan agruparam workloads equivalentes; relatórios, métricas do gerador, CSVs, logs e perfis distinguiram resultado válido, falha operacional e experimento perturbado por instrumentação. Nome de tag e ordem do Git foram usados somente como apoio, nunca como prova isolada de causalidade.

Os experimentos foram agrupados em cinco famílias duráveis: evolução do core e do PostgreSQL; arquiteturas de delivery; redução de overhead e variância do gerador Go; evolução do pacer/ownership do gerador Rust; e exploração acima da meta em `4.000 TPS`. Smokes de cleanup e tentativas interrompidas permaneceram evidência de regressão ou falha operacional, não amostras de performance.

A comparação revelou lacunas reais na síntese anterior. Foram incorporados ao [apêndice de achados experimentais](../../../../performance/experimental-findings.md): a migração de gargalos entre ingresso, PostgreSQL, delivery e gerador; a variância histórica do Go; a diferença entre ganho local e sistêmico; batching observado em vez de configurado; trade-offs físicos do schema; warmup e isolamento; o limite exploratório de `4.000 TPS`; a sequência de tentativas negativas do pacer Rust; o planner compartilhado como mudança decisiva; o custo da instrumentação intrusiva; e o fato de que o Pull vigente normalmente devolveu lotes de `1–3`, embora o teto do protocolo seja `15`.

Não foi criado um catálogo público de todos os runs. O caderno de estabilização continua preservando a cronologia detalhada, enquanto os documentos permanentes mantêm somente conclusões que independem da disponibilidade dos artefatos brutos. Bundles incompletos não foram promovidos a evidência de capacidade, mas suas falhas foram usadas quando distinguiam uma hipótese, como canal maior, pinning, spin prolongado, stack reutilizada e corrida de Pull.

O archive recuperou o bundle `pacs008-fetch-min-56k/20260823_142558`, permitindo reauditar a redução de `128` para `56 KiB`: o lote mediano permaneceu em `165`, enquanto p99/máximo do lote caíram de `281/493` para `235/350`, p99 do callback caiu de `105,511` para `72,191 ms` e p99 end-to-end caiu de `566,941` para `386,178 ms`. Como os dois diagnósticos ficaram abaixo do piso rolling e não constituem um A/B contrabalançado, a conclusão permanente foi limitada a menor espera e cauda observadas, sem atribuir ganho qualificador isolado.

Dos bundles citados pela documentação permanente, somente `baseline-buckets/20260814_023552` continua ausente. Há outro baseline da mesma geração em `baseline-buckets/20260814_093850`, mas ele não foi tratado como substituto nem usado para reconstruir metadata do run perdido.

## Evidência curada

Preservar no Git os artefatos compactos de:

* o Go do A/B final: `load-test/results/compare-go-15m-fixed/20260828_214501`;
* o Rust do A/B final e primeiro candidato qualificador: `load-test/results/compare-rust-15m-fixed/20260828_220502`;
* a nova repetição limpa do Rust na revisão final.

Para cada um, versionar somente profile, plano de execução, relatório final e um manifesto com origem e checksums. Os dois runs Rust sustentam a afirmação final de capacidade; o Go permanece como comparação do gerador anterior.

Não será criado catálogo ou pacote histórico. O conhecimento relevante dos demais runs já foi consolidado no relatório, no apêndice experimental e no caderno histórico. Os bundles brutos permanecem locais e fora do Git.

Antes de copiar os JSONs selecionados para o Git, confirmar que eles não contêm certificados, credenciais, tokens ou caminhos sensíveis desnecessários.

## Ordem de execução

1. Usar um inventário temporário para auditar a cobertura dos experimentos sem alterar os bundles.
2. Incorporar aos relatórios permanentes somente conclusões únicas e evidências relevantes ainda ausentes.
3. Revisar a narrativa consolidada e confirmar que o inventário temporário não integra a documentação final.
4. Validar o candidato atual e executar uma repetição qualificadora limpa.
5. Selecionar formalmente os dois bundles qualificadores finais.
6. Auditar e versionar os artefatos compactos dos três runs selecionados.
7. Atualizar o relatório e o manifesto com a evidência final.
8. Mover esta task para `concluidas`.

## Critérios de conclusão

* A afirmação final de capacidade referencia duas execuções comparáveis do runtime final.
* Os relatórios permanentes preservam as conclusões relevantes dos experimentos sem expor um catálogo de resíduos intermediários.
* O Git contém profile, plano, relatórios finais, origem e checksums dos três runs selecionados.
* O manifesto permite localizar e interpretar a evidência final sem depender dos bundles históricos intermediários.
* CSVs, JFRs e logs volumosos não entram no Git comum.

## Fora de escopo

* Criar visualizações novas ou reprocessar relatórios históricos.
* Inventar metadata ausente nos runs antigos.
* Fazer tuning, alterar SLAs ou redefinir o workload.
* Criar arquivo externo, upload para R2 ou snapshot completo do histórico.
