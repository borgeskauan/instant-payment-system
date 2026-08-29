# Consolidar evidências finais de performance

## Objetivo

Preservar uma evidência final auditável da capacidade de 2.000 pagamentos originais por segundo e conservar a evolução histórica dos experimentos sem manter `load-test/results` indefinidamente descompactado no workspace.

## Estado atual

* [x] A meta foi atingida e repetida durante a estabilização.
* [x] Método, decisões, resultados e limitações estão consolidados no [relatório de estabilização](../../../../performance/2k-tps-stabilization.md).
* [x] Profile, plano e relatórios compactos dos runs originais estão preservados no [manifesto de 2026-08-27](../../../../performance/evidence/2026-08-27/manifest.md).
* [x] O diretório histórico possui aproximadamente `26 GiB`, `93` bundles e `59` relatórios finais.
* [ ] Substituir como evidência final do estado atual os dois runs de 2026-08-27, executados antes do cleanup do core.
* [ ] Versionar um índice compacto do histórico de experimentos.
* [ ] Preparar e validar localmente os arquivos finais e o snapshot histórico.
* [ ] Enviar os arquivos ao Cloudflare R2 e validar a recuperação.

Nada em `load-test/results` deve ser apagado antes da validação completa da cópia recuperada do R2.

## Evidência final atual

O primeiro candidato é `load-test/results/compare-rust-15m-fixed/20260828_220502`. Ele qualificou o profile oficial contra o runtime atual, incluindo a correção do Gateway identificada pelo checksum preservado no bundle e posteriormente incorporada ao Git.

Será executada uma única repetição limpa na revisão final. Os dois runs somente serão promovidos juntos se usarem profile, plano, recursos, instrumentação e runtime equivalentes e ambos satisfizerem throughput, latência e outcomes.

Os runs de `rust-qualification-15m-clean` e `rust-qualification-15m-repeat-clean` continuam fazendo parte da história da estabilização, mas não serão apresentados como prova do código após o cleanup.

## Índice histórico versionado

Preservar no Git um catálogo pequeno e navegável, sem copiar CSVs, JFRs ou logs volumosos. Ausência de metadata em bundles antigos deve ser representada explicitamente, nunca inferida como fato autoritativo.

O índice deve registrar, quando disponível:

* tag, timestamp e caminho original do bundle;
* profile e tipo de gerador;
* revisão Git ou indicação de revisão desconhecida/reconstruída;
* hipótese ou propósito identificável pelo nome e pela documentação existente;
* originais planejados e executados, média e mínimo rolling;
* latências p50, p95 e p99;
* violações funcionais;
* classificação factual do run: completo, incompleto, diagnóstico ou qualificador;
* referência ao objeto externo que contém os artefatos brutos.

O índice não deve inventar conclusões para experimentos que não as registraram. Um resumo em Markdown apresenta os marcos principais; um formato tabular versionado preserva a listagem completa.

## Arquivos externos

Preparar localmente:

```text
performance/final/qualification-<date>.tar.zst
performance/final/confirmation-<date>.tar.zst
performance/final/SHA256SUMS
performance/history/performance-history-<date>.tar.zst
performance/history/SHA256SUMS
```

O snapshot histórico preserva o `load-test/results` congelado antes da limpeza. Os bundles finais também ficam separados para auditoria e recuperação rápidas, mesmo estando incluídos no histórico.

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

1. Gerar e revisar o índice histórico sem alterar os bundles.
2. Validar o candidato atual e executar uma repetição qualificadora limpa.
3. Selecionar formalmente os dois bundles finais.
4. Auditar conteúdo sensível e definir exclusões.
5. Produzir arquivos `tar.zst` e `SHA256SUMS` localmente.
6. Testar integridade e extração local dos arquivos.
7. Entregar ao usuário os arquivos e comandos de upload ao R2.
8. Depois do upload, baixar, conferir checksums e testar recuperação.
9. Atualizar relatório, índice e manifesto com a localização externa definitiva.
10. Solicitar autorização explícita antes de remover resultados locais.
11. Mover esta task para `concluidas`.

## Critérios de conclusão

* A afirmação final de capacidade referencia duas execuções comparáveis do runtime final.
* O Git contém profile, plano, relatórios finais, manifesto e índice histórico compacto.
* Os objetos finais e o snapshot histórico existem no R2 privado com SHA-256 validado após download.
* As instruções permitem localizar, recuperar, verificar e interpretar os artefatos.
* CSVs, JFRs e logs volumosos não entram no Git comum.
* Nenhum resultado local é removido antes da recuperação externa validada e da autorização do usuário.

## Fora de escopo

* Criar visualizações novas ou reprocessar relatórios históricos.
* Inventar metadata ausente nos runs antigos.
* Fazer tuning, alterar SLAs ou redefinir o workload.
* Integrar R2 ao código da aplicação ou automatizar credenciais no repositório.
