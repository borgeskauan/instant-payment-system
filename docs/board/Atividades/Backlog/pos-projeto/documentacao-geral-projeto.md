# Organizar a documentação final do projeto

- [ ] Consolidar a documentação canônica depois do freezing técnico

## Objetivo

Transformar a engenharia distribuída entre código, testes, benchmarks e decisões em evidência compreensível e verificável sobre a capacidade profissional demonstrada pelo projeto.

> A documentação deve maximizar a redução de incerteza por minuto de atenção do leitor.

Explicar o sistema, facilitar execução e melhorar manutenção são meios ou efeitos colaterais. O resultado principal é permitir que um avaliador competente perceba o que foi construído, por que foi construído assim, quais propriedades foram alcançadas, onde estão as evidências e quais são os limites das afirmações.

## Público principal

Um engenheiro backend competente, com pouco tempo e nenhum contexto prévio sobre o projeto.

O caminho principal também deve permitir que um leitor menos técnico compreenda o problema e o resultado, mas não deve sacrificar precisão para tratar todos os públicos como equivalentes.

## Métrica de design: time-to-answer

A posição de uma informação deve ser definida por quão cedo o leitor precisa dela, não apenas pela categoria documental em que ela naturalmente caberia.

| tempo acumulado | o leitor deve conseguir responder |
| --- | --- |
| `30 segundos` | O que é este projeto? Por que ele existe? |
| `2 minutos` | O que há de tecnicamente relevante? Quais são os principais resultados? |
| `5 minutos` | Como o sistema funciona em alto nível? Quais são as principais garantias e limitações? |
| `10 minutos` | Por que as decisões arquiteturais importantes foram tomadas? Quão confiáveis são as afirmações? |
| sob demanda | Como corretude, performance e operação funcionam em detalhe? Onde estão as evidências? |

Se uma explicação extensa não ajuda a responder uma pergunta importante no horizonte adequado, ela não pertence ao caminho principal. Deve virar deep dive opcional, permanecer histórica ou não ser mantida.

## Operações documentais fundamentais

Todo conteúdo canônico deve fazer pelo menos uma destas coisas:

1. **Tornar visível:** revelar engenharia importante que uma leitura superficial do código não mostra.
2. **Dar significado:** explicar por que uma decisão, propriedade, resultado ou limitação importa.
3. **Permitir verificação:** apontar para evidência, método e limites da afirmação.

O critério editorial é:

> Esta informação melhora materialmente a capacidade de alguém avaliar a engenharia do projeto?

## Gate e fronteira

Iniciar somente depois da revisão das evidências finais, do merge e do freezing técnico. Funcionalidades, arquitetura, configuração e contratos do MVP devem estar congelados; trabalho técnico restante deve estar registrado no backlog.

Alterar somente `README.md`, `docs/**` e artefatos estritamente documentais. Não corrigir código, testes, schemas, configuração, scripts ou contratos nesta task. Uma divergência técnica encontrada gera uma task própria.

## Método mínimo

1. **Inventariar por valor:** identificar qual pergunta cada documento responde, para quem, se ainda representa o sistema vigente e se torna engenharia verificável. Marcar duplicação, contradição, conteúdo superado, links quebrados e material sem consumidor.
2. **Desenhar o caminho principal:** estruturar o `README.md` pelos horizontes de `30 segundos`, `2`, `5` e `10 minutos`. Não transformar o README em livro nem organizar sua narrativa apenas por taxonomia técnica.
3. **Separar profundidade:** manter uma única fonte autoritativa para cada assunto e direcionar detalhes de arquitetura, domínio, operação, performance, decisões e reference demo para deep dives claros. Board, investigações e história não são instruções vigentes.
4. **Consolidar evidência:** relacionar afirmações relevantes a código, testes, benchmarks ou artefatos; explicar por que importam e declarar seus limites.
5. **Reduzir:** fundir duplicações e retirar do caminho principal tudo que aumente tempo de leitura sem reduzir materialmente a incerteza.
6. **Validar:** testar links, fidelidade ao código congelado, autoridade das fontes e o time-to-answer com um leitor sem contexto.

## Idioma

O inglês é o idioma canônico da documentação pública e autoritativa. Não manter cópias completas em português e inglês. README, arquitetura, decisões relevantes, protocolos, metodologia e resultados devem existir em inglês; board, scratch notes e história interna podem permanecer em português enquanto não forem promovidos ao caminho canônico.

## Fora de escopo

* mudanças técnicas no sistema;
* novo tuning ou benchmark;
* tradução integral do board e do histórico;
* documentação exaustiva por completude;
* manutenção de documentação bilíngue.

## Teste de aceitação

Entregar o repositório a um engenheiro backend competente sem contexto prévio e verificar se ele responde corretamente às perguntas previstas após `2`, `5` e `10 minutos`, sem ajuda externa.

A task termina quando o caminho principal passa nesse teste, as afirmações importantes são verificáveis, cada assunto vigente possui uma fonte autoritativa, detalhes permanecem acessíveis sob demanda e a documentação reduz incerteza sem impor leitura desnecessária.
