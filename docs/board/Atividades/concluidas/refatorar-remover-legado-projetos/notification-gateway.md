# Cleanup — Notification Gateway

## Estado

| Gate A — negócio | Gate B — técnica | estado | próxima ação |
| --- | --- | --- | --- |
| aprovado | aprovado | concluído | nenhuma; etapa encerrada |

## Objetivo essencial

Garantir que cada PSP obtenha, com isolamento e sem perda silenciosa, as notificações de pagamento destinadas a ele, retomando o consumo após interrupções dentro da janela operacional suportada.

## Inventário de negócio aprovado

| funcionalidade de negócio | decisão |
| --- | --- |
| entregar notificações somente ao PSP destinatário | manter |
| preservar conteúdo e identidade lógica produzidos pelo SPI | manter |
| garantir entrega at-least-once com retomada sem lacunas | manter |
| permitir consumo no ritmo do PSP | manter |
| manter notificações recuperáveis durante sete dias | manter |
| rejeitar progresso inválido, adulterado ou expirado sem criar lacuna silenciosa | manter |

Ownership aprovado: o SPI decide quais notificações existem e seu significado; o Gateway entrega payloads opacos; o PSP mantém progresso durável e idempotência.

## Intervenção técnica aprovada

* manter mTLS, Pull unary, progresso opaco assinado, log durável, long polling, janela recente e leitura histórica;
* manter a topologia fixa da geração vigente e tratar mudança de partições como migração explícita;
* remover assignor explícito, quantidade de partições configurável, constantes duplicadas e fechamento prematuro de Pull;
* manter `TreeMap`: array circular foi mais rápido no microbenchmark, mas o ganho absoluto não justificou substituir coleção padrão por indexação própria;
* simplificar o tailer removendo validação duplicada, agrupamento, ordenação, cópias intermediárias, atomicidade fictícia do cache e o estado redundante `KNOWN_TAIL`;
* manter modelos, coordenação de Pull e outcomes de leitura em `delivery`; o adapter Kafka implementa o port de histórico e o adapter gRPC consome os contratos de delivery em vez de tipos concretos do reader Kafka.

As ambiguidades relevantes de ownership, progresso e sequência foram resolvidas durante os dois gates; nenhuma ambiguidade permanece aberta nesta etapa.

## Evidências

* 40 testes do Gateway passaram sem falha;
* o Compose permaneceu válido;
* `git diff --check` passou.

A revisão final de ownership eliminou a dependência Kafka → gRPC: `PullRequestCoordinator`, os modelos de delivery e os erros semânticos passaram para `delivery`, enquanto `HistoricalKafkaReader` passou a implementar o port `NotificationHistory`. O protocolo, o cursor, a posição Kafka, o cache e o comportamento de long polling permaneceram inalterados.

A configuração própria do componente passou a ser vinculada e validada por `NotificationGatewayProperties`. `application.yml` é o único baseline, os consumidores deixaram de repetir fallbacks em `@Value` e os overrides usam a resolução canônica do Spring. A configuração padrão do consumer Kafka passou a ser lida por `KafkaProperties`, sem reconstruir em paralelo os valores já pertencentes ao Spring Boot.

O teste final da imagem encontrou uma ambiguidade de construção introduzida durante essa centralização: `NotificationGrpcService` possuía dois construtores e o Spring não conseguia selecionar a dependência principal. A classe voltou a ter um único construtor, e um teste de contexto passou a proteger o wiring real da fronteira gRPC. O preparador oficial e o smoke integrado passaram depois da correção.

Dois ganhos locais de performance foram avaliados e deliberadamente rejeitados: evitar `ByteString.copyFrom` economizaria menos de 0,5% de um core, e manter HMAC em `ThreadLocal` economizaria cerca de 0,09% de um core no workload observado. Nenhum estado ou mecanismo próprio foi adicionado por ganhos absolutos tão pequenos.

A arquitetura de entrega vigente está documentada em [`Entrega durável de notificações pelo Kafka`](../../../../architecture/kafka-durable-notification-delivery.md).
