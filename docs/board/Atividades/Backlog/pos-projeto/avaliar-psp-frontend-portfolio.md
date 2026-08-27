# Avaliar PSP simulado e frontend para o portfólio

* [ ] Decidir o destino de `payment-service-provider` e `payment-app` depois da
  estabilização do núcleo do projeto

## Contexto

O PSP simulado e o frontend foram criados para demonstrar o fluxo de pagamentos
e apoiar testes manuais. Desde então, o SPI, o notification-gateway e o
load-tool ganharam responsabilidades e garantias próprias, enquanto esses dois
projetos continuaram aumentando a superfície de código, dependências e
documentação que precisa ser mantida.

Eles não devem permanecer no caminho principal apenas por inércia. Também não
devem ser removidos ou movidos para `legacy/` sem verificar se ainda tornam a
arquitetura e os fluxos do portfólio mais fáceis de demonstrar.

## Objetivo

Tomar e documentar uma decisão independente para cada projeto:

* manter como parte ativa do portfólio;
* preservar em `legacy/` como demonstração histórica e candidato a modernização;
* substituir por uma alternativa menor;
* remover, caso não exista valor demonstrativo ou operacional suficiente.

## Avaliação

* mapear quais demonstrações, testes, scripts, documentação e componentes ainda
  dependem de `payment-service-provider` e `payment-app`;
* separar o papel do PSP simulado no domínio do papel exercido pelo load-tool;
* avaliar o valor de portfólio de cada projeto: conceitos demonstrados,
  experiência de execução e clareza para quem conhece o sistema pela primeira
  vez;
* medir a superfície de manutenção: código, dependências, persistência,
  protocolos, build, vulnerabilidades e testes;
* identificar divergências entre o comportamento atual e a arquitetura vigente;
* avaliar se uma modernização futura reduziria a complexidade ou apenas poliria
  componentes que não fazem parte do núcleo do projeto;
* definir o impacto de mover qualquer projeto para `legacy/`, incluindo layout,
  Compose, documentação, CI e comandos de demonstração;
* revisar as tasks que pressupõem a continuidade desses componentes, em especial
  o control panel dos PSPs e a consistência entre DICT e cadastro local.

## Entrega

Produzir uma decisão curta para cada projeto contendo:

* função que ainda exerce;
* valor para o portfólio;
* custo de manutenção;
* destino escolhido e justificativa;
* dependências que precisam ser migradas ou removidas;
* eventual task futura de modernização, com escopo próprio.

## Critérios de conclusão

* PSP e frontend possuem decisões explícitas e independentes;
* nenhuma decisão se baseia apenas no fato de o código já existir;
* mover para `legacy/` não é apresentado como modernização nem como remoção;
* o caminho principal do projeto continua reproduzível após qualquer mudança de
  organização aprovada;
* documentação e backlog não continuam descrevendo componentes com um status
  diferente do decidido;
* trabalhos de modernização são criados somente quando houver valor de portfólio
  e objetivo demonstrável.

## Fora de escopo

* mover, remover ou modernizar os projetos durante esta avaliação;
* redesenhar o SPI, o notification-gateway ou o load-tool;
* transformar o PSP simulado em uma implementação produtiva de participante;
* adicionar novas funcionalidades ao frontend antes da decisão de destino.
