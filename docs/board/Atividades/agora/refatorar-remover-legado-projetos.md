# Refatorar e remover código legado dos projetos

- [ ] Concluir o cleanup transversal dos projetos que compõem o MVP

## Por que existe

Depois da estabilização funcional e de performance, o repositório ainda pode
conter funcionalidades sem valor para o MVP, caminhos arquiteturais superados,
configurações duplicadas, abstrações sem consumidores e testes que preservam
acidentalmente estruturas antigas.

O objetivo desta task é reduzir essa complexidade de forma deliberada. Primeiro
deve ser definido o objetivo essencial de cada projeto. Depois, suas
funcionalidades são inventariadas e confrontadas com esse objetivo. Cada uma
recebe uma decisão explícita: **manter**, **remover** ou **separar para outra
task**. Somente as funcionalidades mantidas serão simplificadas.

Esta não é uma campanha ampla de tuning nem uma oportunidade para adicionar
features. Depois da redução de escopo e da simplificação, serviços aplicáveis
podem receber ganhos localizados e de baixo risco que criem headroom adicional
sem aumentar a complexidade arquitetural. A ordem é:

```text
definir o objetivo essencial do projeto
        ↓
inventariar funcionalidades
        ↓
avaliar aderência ao objetivo
por simplicidade, performance e manutenção
        ↓
escolher explicitamente o que fica e o que sai
        ↓
remover o que não pertence ao escopo
        ↓
simplificar o que restou
        ↓
procurar ganhos simples e mensuráveis de performance
        ↓
validar os contratos preservados
```

## Resultado esperado

Ao final:

* cada projeto possui uma responsabilidade essencial clara;
* toda funcionalidade mantida contribui diretamente para essa responsabilidade;
* consumidores e evidências de uso são conhecidos, mas não substituem a
  justificativa pelo objetivo;
* funcionalidades removidas não deixam schemas, flags, configurações, testes ou
  documentação residuais;
* as funcionalidades mantidas possuem fronteiras menores e mais legíveis;
* não existem duas fontes concorrentes para a mesma configuração ou regra;
* serviços aplicáveis não preservam trabalho óbvio e evitável em seus hot paths
  essenciais;
* testes protegem comportamento e invariantes, não a existência de estruturas
  antigas;
* a redução de complexidade é observável também no diff e no volume líquido de
  código, sem transformar contagem de linhas em objetivo isolado.

## Método obrigatório por projeto

Cada projeto deve atravessar estas etapas na ordem apresentada:

1. **Definir o objetivo essencial.** Responder em uma frase por que o projeto
   existe no MVP e qual resultado entrega ao sistema.
2. **Inventariar as funcionalidades.** Listar responsabilidades observáveis,
   contratos, mecanismos operacionais e persistência sem decompor o inventário
   em classes.
3. **Revisar a linguagem.** Identificar conceitos ambíguos, termos
   sobrecarregados, sinônimos, nomes herdados de arquiteturas removidas e
   mistura entre linguagem técnica, protocolo e negócio.
4. **Revisar trabalho implícito.** Procurar `TODO`, `FIXME`, `HACK`, `XXX`,
   placeholders e branches deliberadamente incompletos, decidindo o destino de
   cada ocorrência.
5. **Confrontar cada funcionalidade com o objetivo.** Identificar sua
   contribuição direta, seus consumidores e as invariantes que preserva.
6. **Avaliar pelos três princípios.** Examinar simplicidade, performance e
   manutenção, inclusive os custos e riscos de manter ou remover.
7. **Decidir.** Marcar a funcionalidade como `manter`, `remover` ou `separar`,
   com justificativa registrada.
8. **Executar o cleanup.** Remover integralmente o que saiu, corrigir a
   linguagem interna aprovada, apagar testes-túmulo que apenas comprovam a
   inexistência da funcionalidade removida e simplificar apenas o que
   permaneceu.
9. **Avaliar ganhos fáceis de performance.** Somente depois da simplificação,
   inspecionar o hot path essencial em busca de trabalho redundante que possa
   ser removido com mudança localizada e medição focada.
10. **Validar.** Provar os contratos preservados com testes focados antes de
   avançar ao projeto seguinte.

Uma funcionalidade não permanece apenas porque já existe, possui testes ou tem
algum consumidor. Esses sinais demonstram impacto da mudança, mas a decisão é
determinada primeiro pela aderência ao objetivo essencial do projeto.

## Três princípios de avaliação

### Simplicidade

* O projeto contém somente as responsabilidades necessárias ao seu objetivo.
* Cada conceito possui um significado e um owner claros.
* Não existem mecanismos paralelos para resolver o mesmo problema.
* A solução não antecipa extensibilidade, compatibilidade ou falhas fora do
  escopo vigente.

Simplicidade começa pela remoção de funcionalidades. Reorganizar código que não
deveria existir não é simplificação.

### Performance

* O caminho essencial evita trabalho, alocação, serialização, persistência ou
  coordenação desnecessários.
* Mecanismos mantidos no hot path possuem justificativa funcional.
* Uma simplificação não pode introduzir regressão evidente no workload já
  homologado.
* Tuning ou mudança arquitetural motivada exclusivamente por desempenho exige
  evidência própria e fica em task separada.

Performance é uma restrição da solução mantida, não autorização para ampliar o
cleanup com uma sequência aberta de experimentos.

#### O que conta como ganho fácil de performance

Uma mudança de performance só permanece nesta task quando todas estas
condições forem atendidas:

* é localizada e fácil de compreender no diff;
* remove alocações, cópias, serialização, persistência, queries, coordenação ou
  outro trabalho demonstravelmente desnecessário;
* não introduz novo componente, fila, cache, modelo de concorrência, protocolo
  ou configuração operacional;
* não altera contrato de negócio nem transfere responsabilidade entre serviços;
* sua hipótese pode ser avaliada com microbenchmark, teste focado ou execução
  diagnóstica curta;
* preserva o comportamento e demonstra ganho mensurável ou redução evidente de
  trabalho.

Se a mudança exigir redesenho arquitetural, matriz longa de A/B, retuning de
recursos ou várias intervenções dependentes, ela deve virar task própria. O
cleanup busca headroom barato, não uma nova meta de performance.

### Manutenção

* Ownership, configuração e contratos são fáceis de localizar.
* Dependências apontam na direção da responsabilidade essencial.
* Testes protegem comportamento e invariantes em vez da forma interna.
* A implementação pode ser alterada sem exigir conhecimento simultâneo de
  detalhes não relacionados.

Manutenção não significa uniformizar estilo ou criar abstrações genéricas. Ela
deve reduzir o contexto necessário para compreender e alterar o projeto.

### Linguagem e conceitos

A revisão deve procurar especialmente:

* um mesmo nome usado para conceitos diferentes;
* nomes diferentes usados para o mesmo conceito;
* tipos que misturam estado interno, protocolo externo e representação
  persistida;
* nomes que descrevem uma arquitetura já removida;
* termos técnicos apresentados como fatos de negócio;
* flags, propriedades ou métodos cujo nome não corresponde mais ao efeito
  vigente.

Para cada ambiguidade, decidir entre renomear, separar conceitos, consolidar
sinônimos ou remover o conceito obsoleto. Correções internas pertencem ao
cleanup. Alterações de contrato externo ou decisões novas de domínio devem ser
separadas em task própria.

Quando houver tensão entre os princípios, a aderência ao objetivo essencial é
o gate. Entre alternativas que cumprem o objetivo, deve ser preferida a mais
simples que preserve a performance necessária e ofereça uma fronteira
manutenível.

## Critérios para decidir funcionalidades

### Manter

Uma funcionalidade permanece quando contribui diretamente para o objetivo
essencial do projeto e sua necessidade é demonstrada por uma ou mais destas
condições:

* preserva uma invariante funcional, financeira ou de entrega;
* é exercitada por um consumidor vigente;
* é necessária para operar, testar ou diagnosticar o MVP de maneira que não
  possa ser substituída por mecanismo mais simples já existente.

Depois da decisão de manter, ainda devem ser avaliadas duplicações,
responsabilidades espalhadas, abstrações desnecessárias e configurações com
mais de uma fonte autoritativa.

### Remover

Uma funcionalidade deve sair quando:

* não possui consumidor atual;
* preserva uma arquitetura, protocolo ou requisito já abandonado;
* duplica responsabilidade disponível em outro componente ou mecanismo padrão;
* existe somente para compatibilidade histórica que não faz parte do contrato;
* seus testes verificam apenas que a própria estrutura legada continua
  existindo;
* aumenta a superfície operacional sem contribuir para o objetivo do projeto.

A remoção deve ser completa: runtime, configuração, dependência, persistência,
scripts, testes e documentação ativa devem ser revisados juntos.

Testes cujo único propósito é provar que uma funcionalidade antiga continua
ausente também devem ser removidos. Isso inclui testes que procuram classes,
métodos, flags, endpoints, tabelas, colunas ou formatos apagados apenas para
impedir seu retorno. O histórico do Git já registra a remoção; a suíte vigente
deve descrever o sistema que existe.

Essa regra não remove testes negativos que protegem o contrato atual. Entrada
inválida, falta de autorização, divergência idempotente, combinação de estado
proibida e outras violações semânticas vigentes continuam cobertas.

### Separar para outra task

Uma funcionalidade não deve crescer dentro deste cleanup quando exigir:

* decisão nova de produto ou domínio;
* mudança de contrato externo;
* tuning ou benchmark próprio;
* homologação multi-instância, HA ou disaster recovery;
* modernização ampla de um projeto cujo valor para o portfólio ainda não foi
  decidido.

Nesses casos, o inventário registra a decisão e referencia uma task própria.

## Como inventariar cada projeto

Antes de qualquer mudança, registrar primeiro uma frase com o objetivo
essencial e depois uma tabela como esta dentro da seção do projeto:

| funcionalidade | contribuição ao objetivo | consumidor/evidência | simplicidade | performance | manutenção | decisão |
| --- | --- | --- | --- | --- | --- | --- |
| exemplo | resultado que viabiliza | quem usa ou como foi provado | custo conceitual | custo no fluxo | custo de mudança | manter/remover/separar |

O inventário deve considerar não apenas classes e endpoints, mas também:

* persistência e migrations;
* tópicos, consumers e producers Kafka;
* contratos HTTP/gRPC;
* schedulers, retries e recovery paths;
* flags, profiles e propriedades;
* diagnósticos e observabilidade próprios;
* scripts de operação;
* dependências de build;
* testes, fixtures e documentação ativa.

Além da tabela de funcionalidades, cada projeto deve registrar as ambiguidades
conceituais encontradas e a decisão tomada para cada uma. A ausência de
ambiguidade também deve ser confirmada explicitamente; essa revisão não pode
ficar implícita na refatoração.

### TODOs e trabalho implícito

Cada projeto deve ser varrido por marcadores como `TODO`, `FIXME`, `HACK`,
`XXX`, `TBD`, implementações placeholder e branches que retornam valor
temporário ou deixam comportamento deliberadamente incompleto.

Para cada ocorrência:

* resolver durante o cleanup quando for necessária ao objetivo e couber no
  escopo aprovado;
* remover quando estiver obsoleta, já implementada ou ligada a funcionalidade
  descartada;
* criar ou referenciar uma task real quando representar trabalho futuro válido;
* manter comentário local somente quando ele explicar uma restrição técnica que
  não possa ser expressa melhor pelo código — nunca como substituto do backlog.

Código gerado, dependências vendorizadas e referências externas não entram
nessa varredura. Ao final de cada projeto, repetir a busca e justificar qualquer
marcador remanescente.

O inventário não precisa descrever cada classe. A unidade de decisão é uma
funcionalidade ou responsabilidade compreensível externamente.

## Princípios de execução

* Definir o objetivo antes de avaliar funcionalidades.
* Revisar a linguagem antes de reorganizar tipos e fronteiras.
* Não usar comentários TODO como sistema paralelo de backlog.
* Remover conceitos antes de reorganizar código.
* Não substituir uma abstração redundante por um framework genérico.
* Não transformar cleanup em campanha aberta de tuning de performance.
* Não preservar compatibilidade com interfaces que nunca foram contrato.
* Não remover uma invariante apenas porque sua implementação parece complexa.
* Preferir testes semânticos aos testes de estrutura interna.
* Apagar testes-túmulo junto com a funcionalidade removida.
* Fazer uma fronteira por vez e manter cada diff revisável.
* Não executar uma run qualificada de 15 minutos para validar cleanup; testes
  focados e um smoke curto são suficientes, salvo regressão concreta.
* Medir ganhos fáceis de performance proporcionalmente ao risco e reverter os
  que não demonstrarem benefício.

## Escopo por projeto

### Etapa 0 — SPI — cleanup estrutural base concluído; revisões pendentes

- [x] Consolidar auditoria em fatos de negócio.
- [x] Remover infraestrutura e APIs sem consumidores.
- [x] Separar estado persistido, outcome recebido e status de notificação.
- [x] Tornar JDBC a fronteira de persistência.
- [x] Consolidar o schema atual em uma baseline sem apagar a evolução
  arquitetural documentada.
- [x] Centralizar a configuração runtime e remover modelos residuais.
- [x] Validar a mudança com testes completos e smoke funcional.
- [x] Inventariar todos os pontos de falha dos ingressos Kafka do SPI.
- [x] Classificar cada falha como rejeição de negócio, erro definitivo de
  entrada, falha transitória ou defeito interno.
- [x] Verificar se cada erro definitivo de entrada é encaminhado para a DLQ
  correta com contexto suficiente para diagnóstico.
- [x] Verificar se falhas transitórias preservam retry e não confirmam o offset
  prematuramente nem são convertidas imediatamente em DLQ.
- [x] Confirmar que rejeições de negócio esperadas seguem seu fluxo de domínio
  e não são tratadas como falha operacional.
- [x] Remover handlers, publishers, tópicos ou testes de DLQ sem caminho real de
  produção e consolidar duplicações encontradas.
- [x] Cobrir a matriz de falhas com testes semânticos, incluindo destino,
  confirmação do offset e ausência de descarte silencioso.
- [x] Revisar a fronteira hexagonal das classes de persistência de pagamentos e
  status.
- [x] Classificar seus blocos como política de domínio, orquestração da
  aplicação ou mecanismo de persistência.
- [x] Extrair para o core somente decisões puras de admissão/duplicidade,
  reserva de liquidez e transição/replay de status.
- [x] Manter no adapter JDBC SQL, arrays, row mapping, locks determinísticos,
  updates condicionais, mutações agregadas e controle de recursos.
- [x] Preservar a transação, a atomicidade, o batching e a quantidade de idas ao
  banco.
- [x] Testar políticas extraídas sem PostgreSQL e preservar testes de integração
  PostgreSQL para locking, concorrência e atomicidade.

O histórico, as decisões e as evidências estão em
[`Simplificação arquitetural do SPI`](../concluidas/simplificar-arquitetura-spi.md).
O cleanup estrutural já registrado permanece concluído. As revisões de falhas e
da fronteira hexagonal são intervenções adicionais e delimitadas; elas não
autorizam reabrir outras decisões do SPI sem evidência nova.

#### Matriz de falhas do SPI

A classificação autoritativa, suas fronteiras de responsabilidade, mudanças aprovadas e critérios de validação estão em [`SPI Kafka failure and DLQ cleanup`](../../../superpowers/specs/2026-08-28-spi-kafka-failure-dlq-design.md). A task não mantém uma segunda cópia da matriz para evitar divergência.

O objetivo não é enviar toda exceção para DLQ. A revisão deve garantir que:

* mensagens definitivamente inválidas não sejam repetidas para sempre;
* indisponibilidade transitória não cause perda nem descarte definitivo;
* rejeições previstas pelo domínio não contaminem a operação como erro técnico;
* nenhuma exceção seja engolida enquanto o offset é confirmado;
* payload original, causa e identidade de origem sejam suficientes para
  investigar uma mensagem estacionada.

#### Fronteira hexagonal de pagamentos

As classes `IncomingPaymentRequestPersistence` e
`IncomingStatusReportPersistence` concentram mecanismos JDBC legítimos e
decisões de negócio. O objetivo não é dividir arquivos por tamanho nem buscar
pureza arquitetural abstrata. A revisão deve reduzir o contexto necessário para
compreender as regras sem degradar o hot path já homologado.

Direção aprovada:

```text
core
├─ política de admissão e duplicidade
├─ política de reserva de liquidez
└─ política de transição e replay de status

adapter JDBC
├─ leitura e row mapping
├─ inserts e selects em lote
├─ locks e aquisição condicional de transições
├─ deltas agregados de saldo
└─ recursos JDBC
```

Restrições:

* usar poucas classes coesas e stateless;
* não criar interfaces sem múltiplas implementações reais;
* não criar engine genérica de regras, registry ou hierarquia de policies;
* não transformar o port em uma sequência de operações JDBC granulares;
* não acrescentar queries, filas, callbacks transacionais ou DTOs apenas para
  satisfazer uma forma arquitetural;
* permitir que o adapter invoque políticas puras enquanto mantém os mesmos
  locks e a mesma transação;
* medir com teste focado ou diagnóstico curto qualquer alteração material no
  hot path.

O resultado desejado admite mais alguns arquivos, mas deve reduzir branches de
negócio dentro dos adapters, tornar as regras legíveis sem PostgreSQL e manter a
complexidade transacional essencial próxima do JDBC.

Classificação aplicada: admissão/duplicidade, reserva em ordem de origem e transição/replay de status são políticas puras do core; a coordenação entre decisões e persistência permanece nos adapters; SQL, arrays, row mapping, locks, aquisição condicional, deltas agregados e recursos JDBC permanecem mecanismos de persistência. A suíte completa do SPI passou com 200 testes e nenhuma falha, incluindo os testes PostgreSQL de locking, concorrência, rollback e atomicidade.

### Etapa 1 — Notification Gateway

- [ ] Definir sua responsabilidade essencial no modelo pull vigente.
- [ ] Inventariar ingestão Kafka, cursor, cache, fallback/recovery, protocolo
  Pull, autenticação e configuração.
- [ ] Decidir explicitamente o destino de cada funcionalidade.
- [ ] Revisar ambiguidades de linguagem no protocolo Pull, cursor, posição,
  cache, fallback e recovery.
- [ ] Remover resíduos do antigo modelo push/ACK e caminhos sem consumidor.
- [ ] Simplificar ownership, configuração e testes do caminho mantido.
- [ ] Inspecionar o hot path mantido em busca de headroom fácil e mensurável.
- [ ] Executar testes focados do Gateway.

### Etapa 2 — Kafka Producer

- [ ] Definir sua responsabilidade essencial como fronteira de ingresso.
- [ ] Inventariar endpoints, autenticação, transformação, publicação, health,
  configuração e tratamento de falhas.
- [ ] Decidir explicitamente o destino de cada funcionalidade.
- [ ] Revisar ambiguidades entre ingresso HTTP, mensagem Kafka, autenticação e
  regras pertencentes ao SPI.
- [ ] Remover duplicações de regra de negócio e infraestrutura sem uso.
- [ ] Simplificar o caminho de ingresso preservado e seus testes.
- [ ] Inspecionar o hot path mantido em busca de headroom fácil e mensurável.
- [ ] Executar testes focados do Producer.

### Etapa 3 — DICT

- [ ] Confirmar o papel vigente do projeto no MVP e seus consumidores reais.
- [ ] Inventariar APIs, persistência, validações, configuração e operação.
- [ ] Decidir explicitamente o destino de cada funcionalidade.
- [ ] Revisar ambiguidades entre cadastro local, chave Pix, participante e
  autoridade do DICT.
- [ ] Remover código demonstrativo ou residual sem consumidor.
- [ ] Simplificar o contrato mantido e seus testes.
- [ ] Inspecionar caminhos aplicáveis em busca de ganhos fáceis sem ampliar o
  papel do DICT.
- [ ] Executar testes focados do DICT.

### Etapa 4 — Load-tool e scripts de suporte

- [ ] Reafirmar a fronteira entre geração de carga, report e preparação do
  ambiente.
- [ ] Inventariar workloads, validações funcionais, artefatos, diagnósticos,
  scripts e opções públicas.
- [ ] Decidir explicitamente o destino de cada funcionalidade.
- [ ] Revisar ambiguidades entre profile, workload, cenário, geração, outcome,
  diagnóstico e qualificação de performance.
- [ ] Remover flags, formatos, adapters e compatibilidade residual sem uso.
- [ ] Simplificar scripts e módulos preservando workload e outcomes.
- [ ] Inspecionar o hot path do gerador e a orquestração em busca de ganhos
  fáceis que reduzam sua interferência sem adicionar mecanismos.
- [ ] Executar testes Rust, testes shell e smoke curto.

### Etapa 5 — Infraestrutura e documentação compartilhadas

- [ ] Inventariar serviços, profiles, volumes, configurações e scripts do
  Compose usados pelo fluxo vigente.
- [ ] Identificar documentação ativa que ainda descreve arquiteturas
  abandonadas como se fossem atuais.
- [ ] Revisar nomes de serviços, propriedades e conceitos compartilhados que não
  correspondem mais ao ownership vigente.
- [ ] Remover configuração e documentação operacional sem consumidor.
- [ ] Preservar documentos históricos como histórico, deixando explícito quando
  foram superados.
- [ ] Confirmar que README, comandos públicos e arquitetura vigente concordam.

## Fora de escopo

* tuning amplo ou arquitetural de CPU, memória, latência ou throughput;
* novas funcionalidades ou novos cenários de workload;
* execução e homologação multi-instância;
* HA de Kafka/PostgreSQL e disaster recovery;
* modernização do frontend ou do PSP simulado antes da decisão sobre seu valor
  para portfólio;
* reescrever componentes apenas para uniformizar estilo ou tecnologia.

`payment-app` e `payment-service-provider` são avaliados separadamente em
[`Avaliar PSP e frontend para o portfólio`](../Backlog/pos-projeto/avaliar-psp-frontend-portfolio.md).
Eles só entram nesta task depois de uma decisão explícita de permanência.

## Validação final

- [ ] Todos os projetos mantidos passam em suas suítes focadas.
- [ ] O build e a configuração integrada da stack permanecem válidos.
- [ ] Um smoke curto comprova happy path, insufficient funds, notificações e
  replays do workload vigente.
- [ ] Nenhum contrato removido continua aparecendo em configuração ou
  documentação ativa.
- [ ] Nenhum teste permanece apenas para provar a inexistência de uma
  funcionalidade removida.
- [ ] TODOs, FIXMEs, HACKs, placeholders e branches incompletos foram resolvidos,
  removidos ou associados a uma task real; toda ocorrência remanescente possui
  justificativa explícita.
- [ ] As decisões `manter`, `remover` e `separar` possuem justificativa
  registrada por projeto.
- [ ] Ambiguidades conceituais encontradas possuem decisão explícita e nenhum
  termo interno continua representando responsabilidades incompatíveis.
- [ ] A matriz de falhas do SPI comprova DLQ para erros definitivos, retry para
  falhas transitórias, fluxo de domínio para rejeições esperadas e ausência de
  ACK/descarte silencioso.
- [ ] Políticas de pagamento e status são compreensíveis e testáveis fora do
  adapter, enquanto SQL, batching, locks e atomicidade permanecem concentrados
  na implementação JDBC sem round trips adicionais.
- [ ] Ganhos fáceis de performance mantidos possuem evidência antes/depois e não
  adicionam mecanismos ou responsabilidades ao projeto.
- [ ] O estado final e as limitações ficam documentados sem iniciar nova rodada
  de estabilização de performance.

## Critério de conclusão

A task termina quando todas as funcionalidades dos projetos em escopo foram
inventariadas e decididas, as remoções aprovadas foram concluídas, o código
essencial restante foi simplificado onde havia ganho concreto de clareza e a
stack preservou seus contratos nos testes e no smoke final.
