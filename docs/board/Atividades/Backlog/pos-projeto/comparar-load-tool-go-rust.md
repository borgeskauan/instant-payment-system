# Comparar as implementações Go e Rust do load-tool

* [ ] Produzir um relatório técnico comparando a implementação Go descontinuada
  com a implementação final em Rust

## Objetivo

Registrar por que o load-tool foi refeito em Rust e avaliar, com evidências, o
resultado da mudança em três dimensões:

* performance e previsibilidade da geração de carga;
* simplicidade arquitetural;
* manutenção e custo de evolução.

O relatório deve distinguir resultados medidos de inferências e evitar uma
conclusão favorável ao Rust baseada apenas na decisão já tomada.

## Trabalho

* preparar a última versão relevante em Go num ambiente isolado a partir do
  histórico Git, sem reintroduzi-la na branch ativa;
* executar uma campanha A/B com Go e Rust contra a mesma revisão e configuração
  do SPI, usando o mesmo profile, recursos, preparação do ambiente e condições
  do host;
* executar ao menos uma run limpa de 15 minutos por implementação e repetir os
  dois lados se os resultados apresentarem variância ou diferença material que
  uma única amostra não permita interpretar;
* comparar throughput observado, rolling mínimo, jitter, CPU, memória e
  estabilidade apenas onde os artefatos permitirem uma comparação válida;
* comparar quantitativamente tamanho do código, arquivos, dependências e testes;
* comparar responsabilidades, estado concorrente, pacing, filas, fronteiras entre
  geração e relatório e facilidade de diagnóstico;
* registrar vantagens, custos e limitações de cada implementação;
* produzir uma tabela executiva seguida das evidências e da metodologia;
* declarar explicitamente qualquer lacuna que impeça uma comparação direta, sem
  preenchê-la com estimativas apresentadas como medições.

## Critérios de conclusão

* cada afirmação quantitativa referencia um commit, run ou comando reproduzível;
* os bundles e manifestos identificam inequivocamente qual gerador, revisão do
  SPI, profile, configuração e recursos foram usados em cada lado do A/B;
* a ordem das execuções e qualquer diferença de aquecimento ou estado do host
  ficam documentadas;
* diferenças do SPI ou do ambiente não são atribuídas à linguagem ou ao
  load-tool;
* o relatório explica o que melhorou, o que ficou mais complexo e em quais
  condições a implementação Go ainda seria suficiente;
* performance, simplicidade e manutenção recebem conclusões separadas;
* o relatório final fica versionado em `docs/architecture/`;
* limitações que permaneçam mesmo após a campanha A/B são declaradas
  explicitamente.

## Fora de escopo

* reintroduzir ou manter as duas implementações;
* alterar o load-tool ou o SPI durante a comparação;
* ajustar ou fazer tuning de qualquer implementação durante a campanha A/B;
* promover estimativas de microbenchmark a resultados end-to-end;
* criar dashboards ou uma nova camada de visualização.
