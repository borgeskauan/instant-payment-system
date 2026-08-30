# Organizar a documentação final do projeto

- [ ] Transformar a engenharia do projeto em evidência fácil de encontrar, compreender e verificar

## Objetivo

O problema profissional mais amplo é reduzir a incerteza de uma pessoa relevante sobre a capacidade do autor. A documentação contribui tornando visível a engenharia que altera materialmente essa avaliação.

> Tornar a engenharia relevante fácil de encontrar, compreender intuitivamente e verificar tecnicamente, com o mínimo de esforço desnecessário do leitor.

Documentar tudo, produzir um manual de manutenção ou criar uma taxonomia completa não são objetivos. Onboarding e manutenção são benefícios secundários, não critérios para expandir o escopo.

## Público e clareza

O leitor principal é um engenheiro, arquiteto, tech lead, engineering manager técnico ou entrevistador competente, com pouco tempo e nenhum contexto prévio.

O leitor leigo não é um público separado; ele representa uma restrição de clareza. Exigir sofisticação técnica somente quando o problema exigir, nunca familiaridade prévia com este repositório.

Regra de escrita:

> Intuição antes de precisão; comportamento antes de terminologia; motivação antes de mecanismo; concreto antes de abstrato.

Exemplo: primeiro explicar que o mesmo pagamento não pode mover dinheiro duas vezes; depois nomear essa propriedade como idempotência.

## Referência de domínio e tese central

O Pix real é referência do problema, não da solução. A documentação não deve sugerir que o projeto reproduz a arquitetura interna do SPI de produção do Banco Central.

O Pix oferece uma experiência simples — enviar dinheiro uma vez, ao destinatário correto, saber o resultado e recebê-lo rapidamente — apesar de operar em escala enorme. O projeto explora, em escala controlada e com escopo explícito, o que é necessário para preservar propriedades equivalentes sob carga.

A tese técnica que organiza a narrativa é:

> O resultado de performance só é relevante porque o sistema continuou resolvendo a versão difícil do problema de pagamento.

Corretude define o trabalho que precisa acontecer, a arquitetura preserva essas propriedades e o benchmark mede quanto desse trabalho real o sistema sustenta. Não apresentar corretude, arquitetura e performance como histórias independentes.

## Contrato de time-to-answer

| tempo acumulado | o leitor deve conseguir responder |
| --- | --- |
| `30 segundos` | Que problema do Pix inspirou o projeto? O que foi construído? |
| `2 minutos` | O que o sistema demonstrou? Por que o resultado não é trivial? |
| `5 minutos` | O que precisa permanecer verdadeiro para cada pagamento? Como o sistema preserva isso em alto nível? |
| `10 minutos` | Por que a arquitetura foi escolhida? Por que o benchmark é confiável? Onde as afirmações terminam? |
| sob demanda | Onde está a evidência concreta? Como inspecionar a implementação ou reproduzir o sistema? |

## Perguntas de aceitação

### Primeiro contato

1. Que problema observado no Pix real inspirou o projeto?
2. O que foi construído e qual parte é considerada o core?
3. O que o sistema final demonstrou de forma mensurável?

### Por que o resultado importa

4. Por que sustentar esse throughput é um resultado não trivial?
5. O que precisa permanecer verdadeiro para cada pagamento durante a carga?
6. Falhas, repetições e concorrência podem mover dinheiro duas vezes ou fazer um outcome desaparecer?

### Profundidade de engenharia

7. Como a arquitetura preserva essas propriedades?
8. Por que os principais trade-offs arquiteturais foram escolhidos dessa forma?
9. Por que o benchmark merece confiança e o que ele não prova?

Essas perguntas são testes de aceitação, não necessariamente títulos de seções.

## Narrativa principal do README

O `README.md` é a interface do projeto, não uma enciclopédia. Sua progressão conceitual deve ser:

1. O Pix torna um problema difícil simples para o usuário.
2. Este projeto é uma exploração própria e limitada dessa classe de problema.
3. O resultado mensurável aparece cedo, com contexto suficiente para ser interpretado.
4. As propriedades que permaneceram verdadeiras durante o benchmark dão significado ao número.
5. A arquitetura é apresentada como resposta a essas propriedades.
6. A metodologia explica por que o resultado é confiável.
7. Limitações visíveis impedem extrapolações indevidas.
8. Links permitem executar ou aprofundar somente quando o leitor desejar.

O primeiro diagrama deve oferecer o modelo mental do pagamento. Um segundo diagrama pode mapear esse fluxo para os componentes técnicos. Diagramas devem reduzir o que o leitor precisa manter na memória, não apenas decorar a página.

## Estrutura documental mínima

Começar com:

```text
README.md
docs/
├── design.md
└── performance.md
demo/
└── README.md
```

Não criar `correctness.md`, `running.md`, páginas por componente ou novas categorias apenas porque a taxonomia parece organizada.

- `design.md` responde como o sistema preserva as propriedades introduzidas no README: fronteiras, ciclo do pagamento, autoridade, reserva, settlement, idempotência, transações, falhas, entrega assíncrona e trade-offs.
- `performance.md` responde quanto da workload correta foi sustentado e por que a afirmação é confiável: claim, workload, ambiente, critérios, método, resultados, evidência e limites.
- `demo/README.md` torna o fluxo abstrato visível, deixando explícito que PSP, DICT e Angular não recebem as garantias do core.
- Instruções de execução permanecem no README enquanto forem curtas; um documento operacional só nasce se melhorar materialmente a leitura.

Documentos especializados existentes devem justificar seu custo para o leitor. Consolidar, reclassificar como história ou remover quando a informação já tiver uma fonte canônica melhor.

## Método

1. Inventariar cada documento pela pergunta que responde, vigência, consumidor, duplicação, contradição e evidência oferecida.
2. Produzir o README contra o contrato de time-to-answer, sem começar pelos detalhes técnicos.
3. Consolidar o design vigente como explicação causal das propriedades, não como inventário de componentes.
4. Consolidar performance como relação entre claim, workload, método, resultado, evidência e limite; não como diário cronológico de tuning.
5. Ligar afirmações importantes a testes, relatórios, artefatos versionados e implementação relevante.
6. Remover duplicações, conteúdo superado e detalhes que não alterem materialmente a avaliação da engenharia.
7. Validar links, fidelidade ao sistema congelado, vocabulário e respostas nos horizontes de `30s`, `2`, `5` e `10 minutos`.

## Regras editoriais

- Uma ideia importante possui uma única fonte canônica. O README resume e aponta; não replica deep dives.
- Arquitetura responde “por quê?” e “qual propriedade isso preserva?”, não apenas enumera tecnologias.
- Uma afirmação relevante segue, quando aplicável: claim → mecanismo/razão → evidência → limite.
- História aparece somente quando explica uma decisão final importante. O leitor não deve reviver a cronologia do desenvolvimento.
- Limitações devem ser pequenas e visíveis: ambiente local/compartilhado, ausência de HA e multi-instância qualificada, ausência de equivalência com a infraestrutura nacional do Pix e demo fora das garantias do core.
- Manter vocabulário estável para payer, receiver, payment, reservation, outcome e settlement.
- Não usar linguagem autoavaliativa nem marketing vazio. Expor evidência suficiente para o leitor formar a conclusão.
- O inglês é o idioma canônico da documentação pública. Board, scratch notes e história não promovida podem permanecer em português.

## Fluxo editorial

1. Produzir e revisar o README em português como rascunho de conteúdo.
2. Validar ideia, ordem, tom, clareza, cortes e time-to-answer antes de discutir formulações em inglês.
3. Produzir `design.md` e `performance.md` em português somente depois que a narrativa principal estiver estável.
4. Reescrever a documentação aprovada em inglês natural, preservando significado sem fazer tradução literal.
5. Não manter versões públicas completas em dois idiomas. O português é material de trabalho; o inglês será a única versão canônica final.

## Orçamento de complexidade

> Maximizar a redução de incerteza por minuto de atenção do leitor.

Teste de exclusão:

> Se este conteúdo desaparecesse, um leitor competente compreenderia ou avaliaria incorretamente uma parte importante da engenharia?

Se a resposta for não, remover, consolidar ou deixar a implementação falar por si.

Não promover ao caminho principal, salvo relevância arquitetural demonstrada:

- catálogo de classes, endpoints, propriedades Kafka, bibliotecas, campos ou migrations;
- tutoriais genéricos de Pix, Kafka ou Spring;
- cronologia completa, experimentos intermediários e alternativas sem impacto na decisão final;
- arquitetura futura especulativa;
- descrições de componentes sem relação com uma propriedade do sistema.

## Fronteira da task

Alterar somente `README.md`, `docs/**`, READMEs localizados e artefatos estritamente documentais. Uma divergência técnica encontrada gera task própria.

Não alterar código, testes, schemas, configuração, scripts, contratos, workloads ou resultados. Não executar novo tuning ou benchmark.

## Critérios de conclusão

- Um leitor sem contexto responde corretamente às nove perguntas nos horizontes previstos.
- O README apresenta problema, resultado, significado, arquitetura, credibilidade e limites sem virar livro.
- `design.md` e `performance.md` concentram as fontes autoritativas necessárias; novos documentos existem somente quando melhoram a compreensão.
- As afirmações centrais possuem evidência verificável e não excedem o que os artefatos provam.
- Core e reference demo estão claramente separados.
- Conteúdo canônico está em inglês, links funcionam e documentação superada não compete com o sistema vigente.
- O caminho principal revela a capacidade de definir corretude, controlar complexidade, investigar performance e reconhecer limites sem afirmar essas qualidades diretamente.
