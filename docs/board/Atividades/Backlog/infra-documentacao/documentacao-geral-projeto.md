# Organizar a documentação final do projeto

- [ ] Consolidar a documentação canônica depois do freezing técnico

## Por que existe

O projeto acumulou documentação de descoberta, arquitetura, protocolos,
decisões intermediárias, experimentos de performance, coleções de teste e
referências externas. Parte descreve o estado vigente, parte registra evolução
histórica e parte é apenas material temporário. Hoje essas categorias não
possuem fronteiras suficientemente claras, o que dificulta descobrir qual
documento é autoritativo.

Esta task organiza o estado final do conhecimento do projeto. Ela não participa
do desenvolvimento técnico: começa somente depois que funcionalidades,
arquitetura, configuração e contratos estiverem congelados.

## Pré-condição: freezing técnico

Esta task só pode ser movida para `agora` quando:

* a estabilização de performance estiver concluída;
* o cleanup transversal dos projetos estiver concluído;
* as funcionalidades mantidas e removidas estiverem decididas;
* contratos, configuração e arquitetura do MVP não tiverem mudanças técnicas
  pendentes dentro do escopo atual;
* qualquer trabalho técnico restante estiver registrado em tasks futuras.

Depois desse gate, o código congelado é a fonte de verdade para a documentação.

## Fronteira rígida

Durante esta task podem ser alterados somente `README.md`, `docs/**` e outros
artefatos estritamente documentais já identificados no inventário.

Não alterar:

* código de produção ou teste;
* migrations ou schemas;
* arquivos de build;
* Compose, configuração runtime ou profiles;
* scripts de execução;
* contratos HTTP, gRPC ou Kafka;
* comportamento funcional ou de performance.

Se a revisão documental revelar um defeito, ambiguidade técnica ainda não
resolvida ou divergência do código, criar uma task técnica e registrar o
bloqueio. Não corrigir o sistema dentro desta task.

## Objetivo

Produzir uma documentação final na qual seja simples:

* entender o propósito e os limites do sistema;
* executar o projeto localmente;
* compreender o fluxo Pix ponta a ponta;
* localizar a arquitetura e as decisões vigentes;
* entender os contratos e escolhas de modelagem relevantes;
* consultar a metodologia e os resultados de performance;
* distinguir documentação autoritativa de histórico e material de referência;
* retomar o projeto sem depender da cronologia das tasks ou dos experimentos.

## Método

### 1. Inventariar

- [ ] Inventariar `README.md` e todo o conteúdo de `docs/**` por finalidade, não
  apenas por diretório.
- [ ] Identificar o consumidor esperado de cada documento.
- [ ] Detectar conteúdo duplicado, contraditório, superado ou sem consumidor.
- [ ] Identificar links quebrados, referências circulares e documentos sem
  ponto de entrada.

Usar a classificação:

| classe | significado |
| --- | --- |
| canônico | explica o estado vigente e é fonte autoritativa |
| operacional | ensina a executar, testar ou diagnosticar o estado vigente |
| decisão | registra ADR ou decisão arquitetural ainda relevante |
| referência | material externo ou normativo consultado pelo projeto |
| histórico | preserva evolução, experimento ou investigação superada |
| temporário | scratch note sem valor durável |
| duplicado | repete conteúdo coberto por outra fonte autoritativa |

### 2. Definir a arquitetura da informação

- [ ] Definir um ponto de entrada único e óbvio no `README.md`.
- [ ] Definir onde ficam visão geral, execução, arquitetura, domínio,
  protocolos, performance, decisões, referências e histórico.
- [ ] Definir uma única fonte autoritativa para cada assunto.
- [ ] Manter board, evidências históricas e planos claramente separados da
  documentação do estado vigente.
- [ ] Tratar `docs/pacs` como referência externa, sem reescrever seu conteúdo
  indiscriminadamente.

A estrutura final deve nascer do inventário. Não mover centenas de arquivos
apenas para uniformizar diretórios.

### 3. Decidir o destino de cada documento

Para cada item ou conjunto coerente, escolher:

* **manter e consolidar** como documentação canônica;
* **mover/classificar** como operacional, decisão, referência ou histórico;
* **fundir** com outra fonte e remover a duplicação;
* **remover** quando temporário, duplicado ou sem valor documental;
* **preservar sem traduzir** quando histórico ou interno e ainda útil.

A decisão deve considerar simplicidade de navegação, autoridade do conteúdo e
custo de manutenção. Preservar arquivos por inércia não é requisito.

### 4. Consolidar o conteúdo vigente

- [ ] Criar ou revisar a visão geral dos componentes: SPI, Kafka Producer,
  Notification Gateway, DICT e ferramentas de teste mantidas.
- [ ] Explicar como executar o projeto localmente usando os comandos públicos
  finais.
- [ ] Explicar o fluxo Pix ponta a ponta em alto nível.
- [ ] Consolidar arquitetura, decisões importantes, contratos e modelagem de
  domínio sem repetir a mesma explicação em vários documentos.
- [ ] Consolidar metodologia do load-test, workload homologado, resultados e
  limitações de performance.
- [ ] Marcar explicitamente documentos históricos que descrevem arquiteturas
  superadas.

### 5. Validar

- [ ] Verificar links relativos e navegação a partir do `README.md`.
- [ ] Confirmar que nenhum documento canônico contradiz o estado técnico
  congelado.
- [ ] Confirmar que cada assunto importante possui uma única fonte
  autoritativa.
- [ ] Confirmar que histórico e scratch notes não aparecem como instruções
  vigentes.
- [ ] Revisar legibilidade para alguém que não acompanhou a evolução do
  projeto.

## Política de idioma

O inglês é o idioma canônico da documentação ativa do projeto. A documentação
pública e autoritativa do portfólio deve existir somente em inglês; não serão
mantidas cópias completas e sincronizadas em português e inglês, pois isso
criaria duas fontes de verdade e drift documental.

Durante a organização final:

* `README.md`: inglês;
* arquitetura e system design: inglês;
* metodologia do load-test e resultados de benchmark: inglês;
* ADRs e decisões de design importantes: inglês;
* documentação de protocolo ou domínio que explique escolhas de modelagem:
  inglês;
* notas internas, rascunhos, experimentos históricos e investigações
  temporárias: podem permanecer em português.

Um documento histórico ou interno só precisa ser traduzido quando for promovido
a documentação importante, vigente ou autoritativa. Não traduzir o arquivo
inteiro apenas por uniformidade, nem reescrever evidências históricas sem valor
para quem consulta o estado final do projeto.

Quando um documento vigente em português for promovido à documentação
canônica, seu conteúdo deve ser consolidado em inglês. A versão portuguesa não
permanece como cópia paralela a ser mantida: ela pode ser removida ou preservada
apenas como artefato histórico explicitamente identificado. Links e pontos de
entrada devem apontar para a versão canônica em inglês.

- [ ] Identificar os documentos públicos ou autoritativos que devem ser
  traduzidos para inglês.
- [ ] Traduzir somente depois do freezing técnico, junto da consolidação do
  conteúdo vigente.
- [ ] Remover cópias paralelas em português da documentação canônica ou
  classificá-las explicitamente como históricas, sem promessa de sincronização.
- [ ] Confirmar que os pontos de entrada e documentos importantes não misturam
  idiomas sem uma justificativa explícita.

## Fora de escopo

* qualquer mudança técnica no sistema;
* criação de features para facilitar a documentação;
* novo tuning ou benchmark;
* tradução integral de board, planos, scratch notes e experimentos históricos;
* reescrita de referências externas;
* manutenção permanente de documentação bilíngue.

## Critério de conclusão

A task termina quando o `README.md` oferece uma entrada clara, a documentação
canônica em inglês descreve fielmente o estado congelado, cada assunto
importante possui uma fonte autoritativa, histórico e referências estão
claramente classificados, duplicações sem valor foram removidas e nenhuma
mudança de código foi necessária.
