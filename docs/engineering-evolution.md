# Como a evidência mudou o desenho

O [design atual](design.md) explica como o sistema funciona. Este documento responde a outra pergunta:

> Quais problemas fizeram o sistema terminar com esse desenho, em vez das alternativas que apareceram durante o projeto?

O documento reúne as investigações que mudaram uma responsabilidade, uma fronteira arquitetural ou a maneira de representar o problema.

Como carga, critérios e implementação mudaram ao longo do caminho, resultados de fases diferentes não formam um único benchmark. A medição final está em [Performance e evidência](performance.md).

## A evolução em uma página

| Período | Problema que ficou visível | Mudança que permaneceu |
| --- | --- | --- |
| ago–set/2025 | completar o primeiro pagamento ponta a ponta | pagamento, decisão do recebedor e movimentação entre PSPs |
| out/2025 | conexão HTTP e processamento financeiro tinham ritmos diferentes | ingresso assíncrono por Kafka |
| jan–mar/2026 | polling e entrega dentro do processador misturavam responsabilidades | Notification Gateway e gRPC |
| jun/2026 | era possível medir velocidade, mas não explicar o custo | instrumentação, PostgreSQL, processamento em lotes e gerador Go |
| jul–12 ago/2026 | um benchmark sem falhas e repetições exercitava um problema fácil demais | idempotência, DLQ, mTLS, outbox, auditoria e repetições |
| 14–24 ago/2026 | a carga final expôs custos no ingresso, no banco e na entrega | HTTP/2 persistente, saldo por participante, Pull e Kafka durável |
| 25–29 ago/2026 | o próprio gerador introduzia variação temporal | gerador Rust com cadenciamento e admissão explícitos |

## O primeiro fluxo revelou três trabalhos diferentes

O projeto começou em agosto de 2025 com um objetivo direto: fazer um pagamento atravessar o sistema inteiro, permitir que o recebedor decidisse e refletir o resultado nas contas.

A primeira versão reuniu entrada HTTP, processamento financeiro e entrega de notificações no mesmo componente. Ela provou o fluxo, mas também mostrou que eram trabalhos com ritmos diferentes:

* receber uma conexão;
* decidir o que acontece com o dinheiro;
* esperar que o participante busque o resultado.

Os primeiros testes exploraram servidores HTTP, threads, long polling e implementações reativas. Nenhuma configuração específica virou a decisão principal. A mudança que permaneceu foi separar a aceitação da mensagem do processamento do pagamento:

```text
Instituição
 ↓ HTTP
Payment Ingress
 ↓ Kafka
Payment Processor
```

O ingresso passou a fazer apenas validações baratas e publicar no Kafka. O processador permaneceu como autoridade sobre identidade, regras e dinheiro.

Essa fronteira também deixou claro o significado da resposta HTTP: a mensagem foi aceita para processamento; o pagamento ainda não terminou.

## A entrega deixou de pertencer ao processador financeiro

Quando Kafka entrou no caminho principal, o polling HTTP antigo deixou de representar o sistema. O Notification Gateway nasceu para assumir o protocolo de entrega:

```text
Payment Processor
 ↓ Kafka
Notification Gateway
 ↓ gRPC
Instituição
```

No fluxo de entrega, o processador passou a encerrar sua parte ao criar a notificação. O Gateway passou a cuidar de como o participante a recebe.

O teste de carga acompanhou essa mudança: a medição do pagamento deixou de terminar na entrada HTTP e passou a terminar somente quando a confirmação retornava ao pagador.

## Medir velocidade deixou de ser suficiente

Em junho de 2026, alcançar uma taxa alta já não bastava. Era preciso explicar onde o sistema gastava tempo e CPU.

A infraestrutura de teste passou a medir em conjunto:

* uso de CPU e memória dos containers;
* lag do Kafka;
* latência ponta a ponta;
* estatísticas do PostgreSQL;
* perfis JFR das aplicações Java.

Isso mudou o método de trabalho. Cada otimização passou a seguir o mesmo ciclo: localizar um custo, mudar o mecanismo relacionado e repetir o teste completo.

A própria instrumentação também podia alterar o resultado. `log_executor_stats` produziu 41 MB de logs. `heaptrack` elevou o RSS do gerador de aproximadamente 59,6 para 491 MiB e seu p99 de cerca de 254 para 718 ms. Essas ferramentas continuaram úteis para diagnóstico, mas ficaram fora das execuções finais.

O k6 havia encontrado os primeiros limites, porém sua taxa acompanhava a velocidade das respostas. Um gerador em Go trouxe uma taxa independente, diferentes concentrações de tráfego, uma janela final para receber resultados e acompanhamento dos pagamentos completos.

Ao mesmo tempo, o sistema medido ficou mais próximo do problema final:

* PostgreSQL substituiu H2;
* os consumidores Kafka passaram a receber grupos de mensagens;
* persistência e conclusão financeira passaram a trabalhar em lotes;
* valores monetários ganharam uma representação compacta;
* a conversão das mensagens PACS foi movida para a entrada;
* o processador passou a consumir uma mensagem interna menor.

Essa foi a primeira arquitetura pensada para reduzir trabalho por pagamento, não apenas para aceitar mais conexões.

## Os buckets reduziram contenção, mas distorceram o dinheiro

Uma versão do saldo dividia a disponibilidade de cada participante entre 16 registros, chamados buckets. O identificador do pagamento escolhia um deles. Assim, lotes concorrentes do mesmo participante frequentemente bloqueavam registros diferentes.

O mecanismo reduzia contenção, mas criava um problema de negócio: um participante podia ter dinheiro suficiente na soma dos buckets e ainda rejeitar um pagamento porque o registro escolhido estava vazio. A conclusão do pagamento também precisava calcular, bloquear e atualizar buckets dos dois lados.

O desenho seguinte voltou a representar o saldo como uma única disponibilidade por participante e antecipou a reserva:

```text
pagamento admitido
→ valor sai da disponibilidade do pagador

recebedor aceita
→ valor chega ao recebedor

recebedor rejeita
→ reserva volta ao pagador
```

À primeira vista, voltar a um único registro parece aumentar a contenção. O ganho veio de mudar a unidade de trabalho: cada lote bloqueia os participantes uma vez, avalia seus pagamentos em memória, soma os efeitos e faz uma atualização por participante.

Isso remove a disputa **dentro** do mesmo lote e mantém a ordem necessária **entre** transações. Pagamentos do mesmo commit não precisam competir por 16 buckets. Já duas transações concorrentes que usam o mesmo dinheiro ainda precisam esperar uma pela outra.

O desenho final mantém apenas a contenção causada por uma disputa real pelo mesmo saldo.

> Paralelismo útil separa trabalho independente. Ele não precisa fragmentar o estado de negócio nem fazer operações da mesma transação competirem entre si.

## A corretude aumentou o custo — e o valor — do benchmark

Entre julho e agosto, o sistema ganhou propriedades que tornaram a carga mais difícil:

* mensagens inválidas receberam um caminho explícito para DLQ;
* pedidos e respostas repetidos tornaram-se idempotentes mesmo sob concorrência;
* a identidade da instituição passou a vir da conexão mTLS;
* a conclusão financeira passou a criar sua notificação na mesma transação;
* fatos de auditoria passaram a compartilhar a transação de negócio;
* saldo insuficiente virou um resultado esperado;
* replays passaram a fazer parte da carga normal.

Esses mecanismos não eram acessórios. Eles definiram o trabalho mínimo necessário para chamar um pagamento de correto.

A pergunta do benchmark deixou de ser “quantas requisições o sistema aceita?” e passou a ser:

> Quantos pagamentos completos ele sustenta sem mover dinheiro duas vezes, contradizer o resultado ou perder a obrigação de notificar?

## O gerador deixou de esconder atrasos

A primeira execução de referência da campanha final revelou que uma média próxima do alvo podia esconder trabalho atrasado e recuperado em rajadas posteriores.

As regras do benchmark foram corrigidas antes de continuar os ajustes de performance:

* cada pagamento recebe uma fronteira temporal absoluta;
* trabalho atrasado não é carregado para a janela seguinte;
* throughput é verificado em toda janela contínua de um segundo;
* repetições adicionam carga, mas não substituem pagamentos originais;
* a latência termina somente quando o resultado retorna;
* performance e corretude são avaliadas juntas.

O gerador passou a registrar o que realmente começou no momento planejado, em vez de apenas contar quanto trabalho conseguiu terminar mais tarde.

## Conexões passaram a fazer parte da infraestrutura

Depois dessa correção, o primeiro limite apareceu antes do processamento financeiro. A instituição simulada criava conexões TLS repetidamente e consumia o ingresso antes de a carga chegar ao restante do sistema.

Reutilizar conexões com HTTP/1.1 confirmou o mecanismo. O desenho final ficou assim:

* HTTP/2 obrigatório;
* conexões persistentes por participante;
* aquecimento autenticado;
* reserva de capacidade para uma nova requisição HTTP/2 antes da admissão do pagamento.

Uma vaga na fila interna do cliente HTTP não prova que existe capacidade na conexão. Por isso, a admissão reserva espaço para uma requisição HTTP/2 antes do limite temporal. Até esse ponto, o pagamento ainda pode ser registrado como não iniciado. Depois dele, a requisição segue até sua conclusão técnica.

Esse modelo também evita abrir uma nova conexão para cada pagamento e mantém as conexões dos participantes ativas durante a carga.

## PostgreSQL melhorou quando o trabalho ao redor dele diminuiu

Com o ingresso liberado, o PostgreSQL tornou-se o recurso mais pressionado. A investigação passou por concorrência dos consumidores, classificação dos lotes, atualizações de resposta, outbox, auditoria, índices, layout físico e tamanho real dos grupos entregues pelo Kafka.

O desenho final manteve um conjunto de simplificações:

* um fluxo serial por consumidor no ambiente medido;
* classificação e autorização do lote em Java antes das escritas;
* atualizações e inserções em lote;
* arrays + `unnest` nos inserts volumosos;
* nenhum `RETURNING` quando a resposta já existe em memória;
* estados, motivos e valores representados de forma compacta;
* somente índices que servem a consultas ou fatos de negócio reais.

Nem toda query mais rápida melhorou o sistema. Algumas apenas deslocaram custo para outra etapa. Por isso, microbenchmarks passaram a validar o mecanismo local, mas só o resultado end-to-end decidia se a mudança permanecia.

Também foi necessário medir os lotes que realmente chegavam à aplicação. No fluxo de respostas, `max.poll.records=500` produziu média de aproximadamente 163 registros e máximo de 339. Limites como `max.poll.records`, `fetch.min.bytes` e tamanho máximo do Pull influenciam sua formação; nenhum deles garante sozinho quantas mensagens a aplicação receberá por vez.

O layout físico revelou outro trade-off. Reservar metade de cada página para futuras atualizações (`fillfactor=50`) elevou os updates HOT de 22,86% para 100%. Porém, o espaço de tabelas e índices cresceu 46,98% sem aumentar o número de pagamentos concluídos. Representações compactas e a remoção de índices sem consumidores reduziram de forma reproduzível o trabalho SQL e o log de escrita do PostgreSQL (WAL), por isso permaneceram.

O método que restou foi:

```text
medir a carga real
→ remover o primeiro custo acidental dominante
→ observar para onde o limite migrou
→ validar o mecanismo local
→ repetir o fluxo end-to-end
```

Um diagnóstico exploratório em 4.000 TPS marcou o limite dessa campanha. A menor janela de um segundo ficou entre 3.920 e 3.960 pagamentos, e o p99 entre 1,36 e 2,45 segundos. Permitir até 1.000 mensagens por leitura no fluxo de entrada, em vez de 500, não eliminou a diferença e piorou a cauda do processamento do lote.

O sistema permaneceu correto, mas não atingiu os critérios em 4.000 TPS. O experimento localizou o próximo limite no consumidor de pagamentos.

## A entrega confiável ficou mais simples em quatro etapas

A arquitetura final de notificações não apareceu pronta. Cada etapa resolveu um problema e revelou outro:

| Etapa | O que resolveu | Custo que permaneceu |
| --- | --- | --- |
| memória no processador + polling HTTP | primeiro fluxo funcional | conexão, armazenamento temporário e dinheiro no mesmo componente |
| Gateway com push confiável | retirou sessões do processador | ACK, lease, retry e uma segunda cópia persistida |
| Pull com cursor | removeu ACK individual e redelivery ativo | índice e reconciliação no PostgreSQL |
| Kafka como log durável | removeu a segunda fonte de verdade | retenção limitada e dependência operacional do broker |

No Push confiável, cada notificação atravessava uma sequência própria de estados:

```text
delivery
→ claim
→ lease
→ IN_FLIGHT
→ ACK
→ retry ou conclusão
```

Com Pull, a instituição passou a controlar seu próprio progresso. Ela pede tudo depois de seu último cursor durável e só avança depois de processar o lote. Para receber algo novamente, basta reapresentar um cursor antigo; não é preciso um agendador de reentrega.

A primeira versão ainda materializava um índice no PostgreSQL e precisava reconciliá-lo com o histórico. O desenho final retirou esse estado intermediário:

* PostgreSQL cria a obrigação na transação financeira;
* o publicador só remove a outbox depois da confirmação do Kafka;
* Kafka preserva a janela operacional;
* a instituição possui seu progresso durável;
* o Gateway oferece Pull e usa memória apenas para acelerar o caso recente.

PostgreSQL deixou de acompanhar cada entrega, e o Gateway deixou de manter uma segunda fonte de verdade.

## No gerador Rust, o cadenciamento ganhou um único responsável

O gerador Go cresceu enquanto o próprio contrato do teste ainda estava sendo descoberto. Cadenciamento, rede, repetições, resultados e relatório passaram a compartilhar estado e pools fixos. Em execuções longas, pausas do gerador podiam violar o requisito temporal mesmo quando a média parecia correta.

A versão Rust foi desenhada novamente a partir do objetivo final:

* uma thread nativa controla apenas o cadenciamento;
* buckets possuem deadlines absolutos;
* o trabalho é preparado antes da fronteira temporal;
* a capacidade HTTP/2 é explícita;
* filas limitadas não escondem recuperação de atraso;
* geração e relatório possuem fronteiras físicas;
* o relatório roda depois do caminho medido.

Tentativas como aumentar filas, prolongar spin ou fixar CPU não resolveram a responsabilidade compartilhada. A mudança de arquitetura resolveu quem decide se um pagamento começou a tempo sem sobrecarregar o pacer.

Um A/B controlado no mesmo núcleo e perfil tornou a diferença observável:

| Medida | Go | Rust |
| --- | ---: | ---: |
| pagamentos omitidos de 1.890.000 | 6.906 | 55 |
| menor janela contínua de 1 segundo | 1.784 TPS | 2.058 TPS |
| CPU do processo | 875,82 s | 576,86 s |
| resultados ausentes ou contraditórios | 0 | 0 |

O Go continuou correto, mas não sustentou o piso temporal naquela execução. O Rust introduziu um runtime assíncrono, operações atômicas e mais fronteiras internas, porém reduziu o estado compartilhado e tornou a admissão previsível.

A comparação vale para estas duas implementações do gerador sob esta carga.

## O sistema terminou menor do que algumas versões intermediárias

Depois que saldo, auditoria, estados e entrega estabilizaram, as migrações experimentais deram lugar a um schema inicial novo e compacto. O histórico permanece nesta narrativa e no Git. O sistema executado não precisa carregar estruturas abandonadas.

Várias decisões finais removeram mecanismos:

* Kafka separou entrada de processamento;
* o Gateway retirou conexões do processador financeiro;
* um saldo por participante substituiu buckets artificiais;
* Pull eliminou ACK, lease e redelivery ativo;
* Kafka durável eliminou índice e reconciler do Gateway;
* o agrupamento saiu do domínio e ficou nas fronteiras de transporte e persistência;
* o gerador ganhou um único responsável pelo cadenciamento.

O padrão comum foi remover autoridades sobrepostas e filas invisíveis enquanto as garantias de negócio ficavam mais fortes.

Os resultados finais desse caminho estão em [Performance e evidência](performance.md).
