# Engenharia de caos e resiliência operacional

* [ ] Automatizar cenários de falha e recuperação dos componentes críticos do fluxo de pagamentos

## Objetivo

Validar que o sistema se recupera de falhas deliberadamente injetadas sem perder mensagens, duplicar efeitos de negócio ou corromper estado.

Esta é uma task guarda-chuva. Cada classe de falha deve ser refinada em fatias ou tasks focadas quando entrar em execução.

A matriz de workloads continua em [`cenarios-realistas-reprocessamento-load-tool.md`](../agora/cenarios-realistas-reprocessamento-load-tool.md). Aqui, a variável do experimento é a falha operacional introduzida.

## Áreas a explorar

* PostgreSQL indisponível, restart ou perda de conexão;
* Kafka indisponível, restart, falha de publicação, rebalance e redelivery causada por falha;
* restart/crash do SPI durante processamento ou backlog;
* restart do `notification-gateway` com deliveries pendentes ou em andamento;
* PSP offline, interrupção e recuperação do stream gRPC;
* ACK perdido, timeout, retry, lease expirado e redelivery;
* falhas de rede entre componentes quando houver uma hipótese concreta.

Replay ou duplicidade deliberada de `pacs.008` e `pacs.002` continuam pertencendo à matriz funcional do load-tool.

## Invariantes

Quando aplicável, validar que:

* nenhuma obrigação aceita desaparece silenciosamente;
* não ocorre dupla liquidação, débito ou crédito;
* status, auditoria e outbox permanecem consistentes;
* retry e redelivery não criam novas obrigações lógicas;
* deliveries persistidas sobrevivem a restart e reconnect;
* mensagens recuperáveis não vão indevidamente para DLQ;
* após a recuperação da dependência, o sistema converge para um estado consistente.

## Evidências

Cada cenário deve registrar evidências suficientes para correlacionar a falha e a recuperação, como:

* linha do tempo da falha;
* estados antes e depois;
* `EndToEndId`, auditoria, outbox e `communication_id` quando aplicável;
* retries, redeliveries, ACKs e demais sinais relevantes.

## Critérios de conclusão

* cenários relevantes para o MVP possuem automação reproduzível;
* invariantes são verificadas automaticamente;
* existe pelo menos um run funcional por cenário;
* não há perda silenciosa nem efeitos de negócio duplicados;
* falhas encontradas geram correção ou task focada.

## Fora de escopo

* tuning e gates finais de performance;
* runs longos de capacidade;
* garantia `exactly-once` fim a fim;
* testes de segurança;
* duplicidade/replay deliberados do workload;
* definir antecipadamente uma única ferramenta de chaos.
