# Como o desenho evoluiu

O [design atual](design.md) explica como o sistema funciona. Este documento responde a uma pergunta diferente:

> Quais problemas fizeram o sistema terminar com esse desenho, e não com uma das alternativas que existiram durante o projeto?

Esta não é uma cronologia de todos os experimentos. Ela preserva apenas mudanças que alteraram uma responsabilidade, uma fronteira arquitetural ou a forma de representar o problema.

Resultados intermediários não aparecem aqui porque foram produzidos sob workloads e contratos diferentes. A capacidade final, sua metodologia e suas limitações pertencem ao [relatório de performance](performance.md).

## A evolução em uma página

| Período | Pressão encontrada | Mudança durável |
| --- | --- | --- |
| ago–set/2025 | fechar o primeiro fluxo ponta a ponta | pagamento, decisão do recebedor e movimentação entre PSPs |
| out/2025 | o ingresso síncrono e o polling limitavam o desenho | ingresso assíncrono com Kafka |
| jan–mar/2026 | o teste e a entrega não representavam o fluxo assíncrono | Kafka no processamento e Notification Gateway com gRPC |
| jun/2026 | o hot path não era observável nem controlável | instrumentação, gerador Go, PostgreSQL e batching |
| jul–12 ago/2026 | velocidade sem falhas e repetições resolvia um problema fácil demais | idempotência, DLQ, mTLS, outbox, auditoria e replays |
| 14–24 ago/2026 | a workload final expôs custos acidentais no ingresso, banco e delivery | HTTP/2 persistente, saldo por participante, Pull e Kafka durável |
| 25–29 ago/2026 | o próprio gerador introduzia variância temporal | gerador Rust com pacing e admissão explícitos |

## Primeiro, fechar o fluxo de negócio

O repositório começou em 26 de agosto de 2025. Nas primeiras semanas, o objetivo era fazer um pagamento atravessar o sistema inteiro: receber o pedido, permitir que o PSP recebedor tomasse uma decisão e refletir o resultado nas contas.

O primeiro desenho mantinha mais responsabilidades dentro do SPI. Entrada HTTP, processamento financeiro e entrega de notificações estavam próximos, e o recebimento do resultado dependia de consultas HTTP.

Esse modelo foi suficiente para provar o fluxo funcional. Também tornou visível o primeiro problema arquitetural: aceitar uma conexão, processar o pagamento e esperar o consumidor buscar sua notificação eram trabalhos com ritmos e responsabilidades diferentes.

## Separar ingresso de processamento

Os primeiros testes de carga começaram em outubro de 2025. Eles exercitaram long polling, diferentes servidores HTTP, configurações de threads e implementações reativas.

A decisão que sobreviveu não foi uma configuração específica de Tomcat, Undertow ou Netty. Foi separar a aceitação HTTP do processamento central:

```text
PSP
 ↓ HTTP
Kafka Producer
 ↓ Kafka
SPI
```

O ingresso passou a validar a estrutura que conseguia reconhecer de forma barata e publicar o trabalho no Kafka. O SPI permaneceu como autoridade sobre identidade, regras do pagamento e efeitos financeiros.

Essa fronteira permitiu que conexões HTTP e processamento financeiro evoluíssem independentemente. Também tornou explícita uma propriedade importante: resposta de ingresso significa que a mensagem foi aceita, não que o pagamento terminou.

## Retirar a entrega de notificações do SPI

Quando Kafka passou a integrar o fluxo principal, o polling HTTP antigo deixou de representar a arquitetura. Em março de 2026 surgiu o Notification Gateway:

```text
SPI
 ↓ Kafka
Notification Gateway
 ↓ gRPC
PSP
```

O SPI deixou de manter conexões ou sessões dos participantes. Sua responsabilidade terminava na produção da notificação; o Gateway passou a cuidar do protocolo usado para entregá-la.

Isso separou duas autoridades que continuariam distintas até o desenho final:

- o SPI decide o resultado financeiro e cria a obrigação de notificá-lo;
- o Gateway expõe esse resultado ao participante.

O load test também passou a observar a confirmação retornando por essa fronteira. Uma resposta HTTP do ingresso nunca mais seria tratada como conclusão do pagamento.

## Tornar o hot path observável

Em junho de 2026, o problema deixou de ser apenas alcançar uma taxa alta e passou a ser explicar onde o fluxo gastava CPU e tempo.

O harness passou a correlacionar recursos dos containers, Kafka lag, latência ponta a ponta, estatísticas do PostgreSQL e JFR. Essa instrumentação mudou a forma de trabalhar: uma alteração precisava responder a uma hipótese localizada, e o resultado local precisava voltar ao fluxo completo.

A campanha também mostrou que profiling é uma intervenção. `log_executor_stats` gerou 41 MB de logs; `heaptrack` elevou o RSS do gerador de aproximadamente 59,6 para 491 MiB e o p99 de cerca de 254 para 718 ms. Instrumentação intrusiva passou a servir para atribuir custo, nunca para qualificar capacidade.

O K6 havia sido útil para descobrir os primeiros limites, mas seu modelo fechado seguia o ritmo das respostas. Ele foi substituído por um gerador Go com taxa explícita, participantes hot e cold, drain e acompanhamento dos outcomes.

Ao mesmo tempo, o sistema medido ficou mais próximo do problema final:

- H2 deu lugar ao PostgreSQL;
- listeners Kafka passaram a receber lotes;
- pagamentos e settlement ganharam persistência em batch;
- valores monetários passaram a usar uma representação compacta;
- o parsing PACS foi deslocado para a borda;
- o SPI passou a consumir uma mensagem interna menor;
- batching deixou de existir como abstração do domínio e passou a aproveitar o agrupamento do Kafka.

Essa fase criou a primeira arquitetura desenhada deliberadamente para reduzir trabalho por pagamento, não apenas para aceitar mais conexões.

## Dos buckets à reserva agregada por participante

Os buckets foram uma otimização legítima de concorrência. O saldo de um participante era distribuído entre 16 rows escolhidas pelo identificador do pagamento; assim, batches concorrentes do mesmo participante frequentemente disputavam locks diferentes no PostgreSQL.

O custo era duplo. A fragmentação criava liquidez artificial — o participante podia ter dinheiro suficiente na soma das rows e ainda rejeitar um pagamento porque o bucket escolhido não possuía saldo — e o settlement precisava calcular, bloquear e atualizar buckets de pagadores e recebedores.

Em agosto apareceu um modelo que não precisava escolher entre representar melhor o dinheiro e reduzir trabalho de coordenação. Os buckets foram substituídos por uma única disponibilidade por participante, e a reserva passou a acontecer na admissão:

```text
pagamento admitido
→ valor sai da disponibilidade do pagador

recebedor aceita
→ valor é creditado ao recebedor

recebedor rejeita
→ reserva volta ao pagador
```

O ganho de concorrência veio da unidade de trabalho. Para cada batch, o SPI bloqueia uma vez os participantes envolvidos, avalia em memória e em ordem os pagamentos de cada pagador, agrega o delta e faz a mutação física por participante. No aceite, o settlement credita apenas o recebedor; na rejeição, devolve apenas a reserva do pagador.

Isso remove coordenação **dentro** do batch sem remover a serialização **entre** batches. Pagamentos que já pertencem à mesma transação não ganham paralelismo disputando buckets entre si: hash, agrupamento, locks e updates adicionais são apenas trabalho de coordenação antes de um único commit. Já duas transações concorrentes que usam o saldo do mesmo participante ainda precisam esperar uma pela outra para impedir gasto duplo. Participantes diferentes continuam independentes.

Portanto, a row única não é universalmente menos contenciosa que 16 buckets. Ela funcionou porque foi combinada com classificação intrabatch em memória e mutações agregadas: a contenção acidental caiu, enquanto permaneceu somente a contenção que representa disputa real pelo mesmo dinheiro.

Essa mudança consolidou uma regra usada nas decisões posteriores:

> Paralelismo útil separa trabalho independente; ele não precisa fragmentar o estado de negócio nem fazer operações da mesma transação competirem entre si.

## Tornar performance dependente de corretude

Entre julho e o início de agosto, o sistema ganhou propriedades que tornaram a workload mais difícil:

- mensagens inválidas passaram a ter um caminho explícito para DLQ;
- PACS.008 e PACS.002 repetidos passaram a ser idempotentes mesmo sob concorrência;
- a identidade do PSP passou a vir da conexão mTLS;
- a conclusão financeira passou a criar sua notificação na mesma transação por meio de uma outbox;
- fatos de auditoria passaram a compartilhar a transação de negócio;
- saldo insuficiente passou a ser um outcome esperado;
- replays passaram a fazer parte do workload normal.

Essas mudanças não foram acessórios do benchmark. Elas definiram qual trabalho precisava acontecer para um pagamento contar como correto.

O objetivo deixou de ser “quantas requests o sistema aceita?” e passou a ser:

> Quantos pagamentos completos ele sustenta sem mover dinheiro duas vezes, contradizer o outcome ou perder a obrigação de notificar?

## Tornar a carga temporalmente honesta

A campanha final de estabilização começou em 14 de agosto de 2026. Seu primeiro baseline mostrou que a média podia permanecer próxima do alvo mesmo quando o gerador atrasava e recuperava trabalho em rajadas posteriores.

O contrato foi redesenhado antes de continuar o tuning:

- cada pagamento original recebe uma fronteira temporal absoluta;
- trabalho atrasado não é carregado para uma janela posterior;
- throughput é verificado em toda janela contínua de um segundo;
- replays são carga adicional, não substitutos dos pagamentos originais;
- a latência termina somente quando o outcome retorna;
- performance e corretude são avaliadas na mesma execução.

Isso mudou a função do load tool. Ele deixou de ser apenas um produtor de tráfego e passou a ser também a autoridade sobre o trabalho que realmente começou dentro da janela planejada.

## Manter conexões como infraestrutura do participante

Com a workload temporalmente observável, o primeiro limite apareceu antes do processamento financeiro. O PSP simulado criava conexões TLS repetidamente e consumia a capacidade do ingresso antes que a carga alcançasse o core.

Um pool HTTP/1.1 confirmou que reutilizar conexões mudava o mecanismo dominante. O desenho final tornou essa propriedade explícita:

- HTTP/2 obrigatório;
- conexões persistentes por participante;
- prewarm autenticado;
- capacidade de stream reservada antes de admitir o pagamento.

A disponibilidade de stream não pode funcionar como uma fila escondida depois do deadline. Antes do commit temporal, o pagamento ainda pode ser descartado como não iniciado; depois dele, a requisição precisa seguir até sua conclusão técnica.

Essa fronteira alinha o gerador ao comportamento esperado de um PSP: a infraestrutura permanece conectada por longos períodos, em vez de abrir uma conexão para cada pagamento.

## Otimizar PostgreSQL sem otimizar somente uma query

Depois que o ingresso deixou de impedir a carga, PostgreSQL apareceu como o recurso mais pressionado. O trabalho passou por classificação intrabatch, concorrência dos listeners, updates PACS.002, inserts da outbox, índices, layout físico, auditoria e tamanho efetivo dos batches Kafka.

O desenho final incorporou simplificações locais:

- um fluxo serial por consumer na stack qualificada;
- classificação e autorização do lote em Java antes das escritas;
- updates e inserts em batch;
- arrays + `unnest` em inserts volumosos;
- ausência de `RETURNING` quando o resultado já existe em memória;
- representação compacta de estados, motivos e valores;
- somente índices ligados a consultas ou fatos de negócio reais.

Algumas queries ficaram muito mais rápidas sem melhorar o fluxo completo. Outras pareciam melhores isoladamente e pioraram o sistema ao adicionar trabalho em outro ponto.

Os lotes também precisaram ser medidos, não inferidos da configuração. Por exemplo, `max.poll.records=500` no fluxo `pacs.002` produziu média de aproximadamente 163 records e máximo de 339. Parâmetros como `max.poll.records`, `fetch.min.bytes` e o limite do Pull são tetos ou condições de formação; não prometem a cardinalidade que chegará à aplicação. O tuning passou a observar a distribuição real e a cauda dos callbacks.

O layout físico apresentou outro trade-off. `fillfactor=50` levou os updates HOT de 22,86% para 100%, mas aumentou heap mais índices em 46,98% e não alterou materialmente os outcomes. Representações compactas e a remoção de índices sem consumidores reduziram SQL e WAL de forma reproduzível. Essas escolhas foram mantidas pelo mecanismo físico que melhoravam, não por uma diferença isolada na cauda end-to-end.

Por isso, a regra de tuning terminou assim:

```text
medir a workload real
→ remover o primeiro custo acidental dominante
→ observar para onde o limite migrou
→ validar o mecanismo local
→ repetir o fluxo end-to-end
```

Um diagnóstico exploratório a 4.000 TPS delimitou onde essa campanha terminava. O mínimo rolling ficou entre 3.920 e 3.960 TPS e o p99 entre 1,36 e 2,45 segundos. Aumentar `max.poll.records` do `pacs.008` de 500 para 1.000 não fechou a diferença e elevou a cauda do callback. O sistema permaneceu correto, mas não qualificou; o experimento localizou a próxima fronteira no consumer `pacs.008` sem transformar 4.000 TPS em claim do projeto.

## De reliable push a um log durável consultável

A entrega de notificações passou por mais etapas do que o design atual deixa aparente.

### Entrega inicial

O SPI mantinha notificações em memória e os participantes as buscavam por HTTP. Era simples, mas ligava lifecycle de conexão, armazenamento temporário e processamento financeiro.

### Gateway com push

Kafka e gRPC retiraram conexões do SPI. Depois, ACK, retry e persistência tornaram o push confiável. O custo foi um lifecycle completo no PostgreSQL do Gateway:

```text
delivery
→ claim
→ lease
→ IN_FLIGHT
→ ACK
→ retry ou conclusão
```

O banco financeiro já guardava a outbox; o Gateway passou a guardar outra cópia da notificação e o estado individual de cada entrega.

### Pull com cursor

O protocolo foi invertido. O PSP passou a pedir notificações depois de seu último cursor durável e só avançá-lo depois de processar o lote.

Isso removeu ACK individual, lease e redelivery ativo. Repetir a entrega passou a significar simplesmente reapresentar um cursor antigo.

### Kafka como log durável

Uma primeira versão do Pull ainda materializava um índice no PostgreSQL e precisava reconciliá-lo. Mesmo sem falha, o custo das varreduras crescia junto com o histórico.

O desenho final atribuiu a cada tecnologia apenas uma autoridade:

- PostgreSQL cria a obrigação de notificar na transação financeira;
- o publisher remove a linha da outbox somente depois da confirmação do broker;
- Kafka preserva a janela operacional de notificações;
- o PSP mantém seu progresso durável;
- o Gateway oferece Pull e usa memória apenas como acelerador.

Assim, o PostgreSQL deixou de acompanhar cada entrega e o Gateway deixou de manter uma segunda fonte de verdade.

## Do Go ao Rust: um owner para a fronteira temporal

O gerador Go cresceu enquanto o contrato do experimento ainda estava sendo descoberto. Pacing, networking, replay, outcomes e relatório compartilhavam estado e pools fixos. Em execuções longas, pequenas pausas do próprio gerador podiam violar a propriedade temporal mesmo quando a média permanecia adequada.

A versão Rust foi tratada como greenfield. A mudança importante não foi apenas a linguagem:

- uma thread nativa possui o pacing;
- buckets usam deadlines absolutos;
- o trabalho é preparado antes da fronteira temporal;
- a admissão HTTP/2 possui capacidade explícita;
- filas são limitadas e não escondem catch-up;
- geração e relatório possuem fronteiras físicas;
- o relatório é produzido depois do caminho medido.

Tentativas locais como aumentar canais, prolongar spin ou fixar CPU não resolveram a responsabilidade compartilhada. O redesenho resolveu o ownership da admissão sem colocar mais trabalho dentro do pacer.

O Rust permaneceu porque tornou a fronteira temporal previsível, não porque uma linguagem foi declarada universalmente superior à outra.

Um A/B controlado no mesmo core e perfil tornou essa decisão observável:

| Medida | Go | Rust |
| --- | ---: | ---: |
| originais omitidos de 1.890.000 | 6.906 | 55 |
| menor janela contínua de 1 segundo | 1.784 TPS | 2.058 TPS |
| CPU do processo | 875,82 s | 576,86 s |
| outcomes ausentes ou contraditórios | 0 | 0 |

O Go continuou funcionalmente correto, mas não sustentou o piso temporal naquela execução. O Rust introduziu mais conceitos locais — Tokio, atomics e um workspace com fronteiras explícitas — em troca de menos estado compartilhado e maior previsibilidade. É uma comparação entre estas duas arquiteturas sob esta workload, não uma conclusão geral sobre as linguagens.

## Consolidar somente depois da experimentação

O schema executável atual não reproduz todas as arquiteturas pelas quais o projeto passou. Depois que saldo, auditoria, estados e delivery estabilizaram, as migrations experimentais foram substituídas por um baseline novo e compacto.

Isso reduz o custo permanente do runtime sem apagar por que buckets, estados amplos, índices técnicos e lifecycle mutável da outbox deixaram de existir.

## O desenho que restou

A evolução não foi uma sequência de tecnologias progressivamente mais sofisticadas. Em vários pontos, o sistema terminou mais simples:

- Kafka separou ingresso de processamento;
- o Gateway retirou conexões do SPI;
- um saldo por participante substituiu buckets artificiais;
- Pull eliminou ACK, lease e redelivery ativo;
- Kafka durável eliminou o índice e o reconciler do Gateway;
- batching saiu do domínio e passou para as fronteiras de transporte e persistência;
- o gerador ganhou um owner explícito para o pacing.

O padrão comum foi remover autoridades sobrepostas e filas invisíveis enquanto as garantias de negócio ficavam mais fortes.

O resultado final é qualificado em [performance e evidência](performance.md).
