# Refatorar e remover código legado dos projetos

- [x] Concluir o cleanup dos projetos que compõem o MVP

## Objetivo

Reduzir a complexidade do repositório projeto por projeto. Primeiro decidimos o que cada projeto precisa entregar ao negócio. Depois removemos funcionalidades e código sem função, simplificamos a implementação mantida e somente então procuramos ganhos localizados de performance.

O cleanup segue esta ordem de prioridade:

1. simplicidade através da adesão ao objetivo;
2. manutenção;
3. performance e previsibilidade.

## Metodologia obrigatória

```text
baseline conhecida e diff revisável
        ↓
objetivo essencial
        ↓
inventário de negócio
        ↓
ambiguidades de negócio identificadas e resolvidas
        ↓
decisões manter/remover/separar
        ↓
GATE A — aprovação explícita do escopo pelo usuário
        ↓
inventário técnico
        ↓
ownership, arquitetura, código morto, linguagem, falhas e ambiguidades técnicas
        ↓
classificação e proposta de intervenção
        ↓
GATE B — aprovação técnica explícita do usuário
        ↓
remoção de escopo e código morto
        ↓
simplificação do que restou
        ↓
ganhos fáceis e medidos de performance
        ↓
validação e registro das evidências
```

Este fluxo mínimo é inegociável para absolutamente todos os projetos em escopo. Particularidades podem acrescentar trabalho, mas nunca substituir, abreviar ou pular uma etapa ou um gate.

## Fase 0 — Estabelecer a baseline

Antes de analisar um projeto:

* manter a working tree revisável e separar mudanças não relacionadas;
* executar a suíte ou os testes representativos do estado vigente;
* registrar o comportamento público e as invariantes conhecidas;
* não misturar cleanup com feature, tuning amplo ou redesign independente.

A baseline serve para detectar regressões. Ela não justifica a permanência de uma funcionalidade.

## Fase 1 — Definir o negócio

### 1. Definir o objetivo essencial

Responder em uma frase por que o projeto existe no MVP e qual resultado entrega ao sistema ou aos seus usuários.

### 2. Inventariar somente as funcionalidades de negócio

Listar capacidades, resultados e garantias observáveis. Classes, banco, Kafka, cache, cursor, protocolo, scheduler, configuração e scripts não são funcionalidades de negócio.

| funcionalidade de negócio | contribuição ao objetivo | consumidor/evidência | proposta |
| --- | --- | --- | --- |
| exemplo | resultado que viabiliza | quem depende da garantia | manter/remover/separar |

Uma funcionalidade não permanece apenas porque possui código, testes ou consumidor. Esses sinais demonstram impacto; a decisão continua sendo determinada pela aderência ao objetivo.

Durante o inventário, identificar ambiguidades de negócio que permitam interpretações diferentes sobre conceitos, garantias, responsabilidades ou resultados. Cada ambiguidade relevante deve receber uma resolução proposta e ser decidida antes do Gate A.

### 3. Avaliar e propor o escopo

Cada funcionalidade recebe uma proposta explícita:

* `manter`: contribui diretamente para o objetivo e preserva uma garantia vigente;
* `remover`: não pertence ao objetivo, duplica outra responsabilidade ou preserva requisito abandonado;
* `separar`: exige decisão nova de produto, contrato externo, arquitetura, tuning, HA ou homologação própria.

### Gate A — Aprovar o negócio

Este é um gate obrigatório. Nenhuma alteração de código, configuração, schema, teste ou documentação do projeto começa antes de o usuário aprovar o inventário de negócio, as propostas de manter, remover ou separar e a resolução das ambiguidades de negócio identificadas.

Aprovação presumida, decisão registrada apenas pela ferramenta ou existência de um plano técnico não satisfazem o gate.

## Fase 2 — Diagnosticar a implementação

### 4. Inventariar a implementação aprovada

Somente depois do gate, mapear como as funcionalidades aprovadas são implementadas:

* contratos HTTP, gRPC e mensagens;
* persistência e migrations;
* producers, consumers, tópicos e filas;
* retries, recovery, schedulers e failure paths;
* configuração, profiles e scripts;
* dependências de build;
* diagnósticos e observabilidade próprios;
* testes, fixtures e documentação ativa.

O inventário técnico deve distinguir complexidade essencial, trade-off arquitetural consciente, otimização opcional e complexidade acidental.

### 5. Revisar ownership, arquitetura e linguagem

A arquitetura hexagonal é uma ferramenta para tornar ownership e direção de dependências claros, não um objetivo estético nem uma estrutura obrigatória para todo projeto.

Quando houver regras de domínio relevantes:

```text
core
├─ decisões e invariantes puras de negócio
└─ linguagem de domínio

aplicação
└─ coordenação de casos de uso e transações

adapters
└─ HTTP, Kafka, gRPC, SQL, locks, serialization e recursos externos
```

Política de negócio difícil não deve permanecer escondida em adapters de persistência. SQL, row mapping, batching, locks, transação e controle de recursos também não devem ser movidos artificialmente para o core.

Serviços de integração simples não precisam receber ports, interfaces ou camadas fictícias apenas para parecer hexagonais. Uma extração só permanece quando reduz o contexto necessário para entender uma regra, esclarece ownership ou permite testar uma decisão sem infraestrutura.

Nesta mesma revisão, identificar ambiguidades técnicas que possam alterar o entendimento do comportamento, ownership, estado, failure handling ou terminologia, como:

* o mesmo nome para conceitos diferentes ou nomes diferentes para o mesmo conceito;
* termos herdados de arquiteturas removidas;
* mistura entre estado interno, protocolo e persistência.

Cada ambiguidade técnica relevante deve receber uma resolução proposta antes do Gate B. Quando nenhuma for encontrada, o diagnóstico deve registrar isso explicitamente.

### 6. Revisar código morto, falhas e trabalho implícito

Identificar duas categorias sem remover nada nesta fase:

1. **Escopo funcional morto:** funcionalidade inteira rejeitada no Gate A, incluindo todos os artefatos que existem somente para sustentá-la.
2. **Implementação morta:** dentro das funcionalidades mantidas, classes, métodos, branches, propriedades, dependências, adapters, endpoints e testes sem caminho real de produção ou consumidor vigente.

Antes de declarar código morto, considerar entrypoints de framework, reflexão, serialização, migrations, scripts, configuração e operação. Teste não é consumidor suficiente e código aparentemente não referenciado pode ser ativado externamente.

Mapear também entrada inválida, rejeição de negócio, falha transitória, defeito interno, retry, DLQ, ACK ou commit, rollback e descarte silencioso. Procurar `TODO`, `FIXME`, `HACK`, `XXX`, placeholders e branches incompletos.

### 7. Classificar e propor a intervenção

Cada mecanismo técnico recebe uma classificação:

* `essencial`;
* `trade-off justificado`;
* `complexidade acidental`;
* `código morto`;
* `otimização opcional`;
* `separar para outra task`.

O diagnóstico apresentado deve incluir ambiguidades e suas resoluções propostas, problemas de ownership, código morto, mudanças propostas, riscos e validação prevista.

### Gate B — Aprovar a intervenção técnica

Este é o segundo gate obrigatório. Nenhuma remoção ou refatoração começa antes de o usuário aprovar o diagnóstico técnico, as resoluções propostas para as ambiguidades técnicas e a intervenção proposta. A aprovação do negócio no Gate A não autoriza automaticamente qualquer implementação técnica.

## Fase 3 — Executar o cleanup aprovado

### 8. Remover escopo rejeitado e código morto

Existem duas remoções diferentes e ambas são obrigatórias:

1. **Remoção funcional:** apagar verticalmente funcionalidades de negócio rejeitadas, incluindo runtime, configuração, schema, dependências, scripts, testes e documentação ativa.
2. **Remoção técnica:** dentro das funcionalidades mantidas, apagar classes, métodos, branches, propriedades, dependências, adapters, endpoints e testes sem caminho real de produção ou consumidor vigente.

Antes de declarar código morto, considerar entrypoints de framework, reflexão, serialização, migrations, scripts e operação. Código aparentemente não referenciado pode ser ativado externamente; a evidência precisa cobrir essas fronteiras.

Testes cujo único propósito seja provar que uma funcionalidade antiga continua ausente também devem sair. Testes negativos que protegem o contrato atual, como autorização, entrada inválida, idempotência e estados proibidos, permanecem.

### 9. Simplificar o que permaneceu

Depois das remoções, reduzir estados, branches, estruturas intermediárias, validações duplicadas, fontes concorrentes de configuração e abstrações sem função. Preferir mecanismos padrão quando uma implementação proprietária não entrega benefício material.

Resolver nesta etapa as ambiguidades internas aprovadas, ajustando nomes, ownership, fonte de verdade, lifecycle ou classificação de falhas conforme a decisão tomada. Ambiguidades que dependam de novo contrato externo ou decisão de produto devem ser separadas e vinculadas a outra task, nunca deixadas silenciosamente em aberto.

Não substituir uma abstração redundante por framework genérico, registry, hierarchy ou extensibilidade antecipada.

### 10. Procurar ganhos fáceis de performance

Performance não autoriza uma nova campanha de tuning. Uma mudança só pertence ao cleanup quando é localizada, remove trabalho demonstravelmente desnecessário, não adiciona componente ou responsabilidade e pode ser avaliada com microbenchmark, teste focado ou diagnóstico curto.

Resultado mais rápido em microbenchmark não vence automaticamente simplicidade e manutenção. Mudanças arquiteturais, matriz longa de A/B ou tuning de recursos viram tasks próprias.

## Fase 4 — Validar e registrar

### 11. Provar o resultado

Ao concluir cada projeto:

* executar testes focados e build;
* validar configuração integrada quando aplicável;
* executar smoke curto quando houver fronteira externa relevante;
* executar `git diff --check`;
* repetir buscas por resíduos e trabalho implícito;
* confirmar que toda ambiguidade identificada foi resolvida ou deliberadamente separada para outra task;
* registrar decisões, limitações e evidências;
* manter o diff revisável antes de avançar.

## Estado por projeto

| projeto | Gate A — negócio | Gate B — técnica | estado | próxima ação |
| --- | --- | --- | --- | --- |
| [SPI](refatorar-remover-legado-projetos/spi.md) | aprovado | aprovado | concluído | nenhuma; etapa encerrada |
| [Notification Gateway](refatorar-remover-legado-projetos/notification-gateway.md) | aprovado | aprovado | concluído | nenhuma; etapa encerrada |
| [Kafka Producer](refatorar-remover-legado-projetos/kafka-producer.md) | aprovado | aprovado | concluído | nenhuma; etapa encerrada |
| [Load-tool e scripts](refatorar-remover-legado-projetos/load-tool.md) | aprovado | aprovado | concluído | nenhuma; etapa encerrada |
| [Ambiente local compartilhado](refatorar-remover-legado-projetos/infraestrutura-local.md) | aprovado | aprovado | concluído | nenhuma; etapa encerrada |

## Registros por projeto

Inventários, ambiguidades, diagnósticos, decisões de implementação e evidências ficam no arquivo próprio de cada projeto. O arquivo é criado quando o cleanup daquele projeto começa; esta task conserva somente a metodologia, os gates, o estado e a próxima ação.

Registros existentes:

* [SPI](refatorar-remover-legado-projetos/spi.md);
* [Notification Gateway](refatorar-remover-legado-projetos/notification-gateway.md);
* [Kafka Producer](refatorar-remover-legado-projetos/kafka-producer.md);
* [Load-tool e scripts](refatorar-remover-legado-projetos/load-tool.md);
* [Ambiente local compartilhado](refatorar-remover-legado-projetos/infraestrutura-local.md).

Load-tool e infraestrutura devem repetir integralmente a metodologia obrigatória. Nenhum inventário técnico começa antes do Gate A e nenhuma alteração começa antes do Gate B.

`dict`, `payment-app` e `payment-service-provider` continuam fora desta task até as decisões registradas em [`Avaliar DICT, PSP simulado e frontend para o portfólio`](../Backlog/pos-projeto/avaliar-dict-psp-frontend-portfolio.md).

## Fora de escopo

* novas funcionalidades ou novos workloads;
* tuning amplo ou redesenho motivado por performance;
* execução multi-instância, acompanhada separadamente em [`Homologar execução multi-instância`](../Backlog/operacao-testes/homologar-execucao-multi-instancia.md);
* HA de Kafka/PostgreSQL e disaster recovery;
* reescrever componentes apenas para uniformizar estilo ou tecnologia;
* documentação canônica final antes do freezing técnico.

## Critério de conclusão

- [x] Todos os projetos em escopo possuem objetivo e inventário de negócio aprovados pelo usuário.
- [x] Todas as decisões `manter`, `remover` e `separar` estão registradas.
- [x] Todos os projetos possuem diagnóstico técnico apresentado e intervenção aprovada explicitamente no Gate B.
- [x] Funcionalidades rejeitadas e código morto foram removidos verticalmente.
- [x] Ownership e dependências deixam regras de negócio fora de adapters técnicos sem impor camadas artificiais.
- [x] Ambiguidades, failure paths e trabalho implícito foram resolvidos ou associados a tasks reais.
- [x] Simplificações mantidas reduziram o contexto necessário para compreender o sistema.
- [x] Ganhos de performance incluídos possuem evidência e não adicionam arquitetura.
- [x] Testes focados, configuração integrada e smoke final preservam os contratos aprovados.
- [x] O estado final e suas limitações estão registrados para a posterior reorganização da documentação.

## Evidência integrada de encerramento

Em 28/08/2026, o preparador oficial recriou volumes e containers, validou readiness, provisionou os participantes e publicou o ambiente `mixed-outcomes-smoke`. A execução pública subsequente gerou os 1.050 pagamentos originais planejados, sustentou rolling mínimo de 103 TPS para o piso de 100 TPS, observou 1.300 outcomes corretos sem ausência ou contradição, aceitou 65 de 65 replays PACS.008 e 51 de 51 replays PACS.002 e permaneceu abaixo do SLA de 1 segundo com p99 de 285 ms e máximo de 318 ms. O resultado local está em `load-test/results/cleanup-final-rerun/20260828_111613`.
