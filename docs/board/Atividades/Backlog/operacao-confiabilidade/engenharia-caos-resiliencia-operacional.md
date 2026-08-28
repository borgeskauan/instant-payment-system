# Engenharia de caos e resiliência operacional

- [ ] Automatizar cenários de falha e recuperação dos componentes críticos do fluxo de pagamentos

## Objetivo

Validar que o sistema se recupera de falhas deliberadamente injetadas sem perder obrigações aceitas, duplicar efeitos financeiros ou corromper estado.

Esta é uma task guarda-chuva. Cada classe de falha deve ser refinada quando entrar em execução. A matriz funcional já foi concluída em [`cenarios-realistas-reprocessamento-load-tool.md`](../../concluidas/cenarios-realistas-reprocessamento-load-tool.md); aqui, a variável do experimento é a falha operacional.

## Arquitetura vigente

As notificações usam Kafka como log durável com retenção contratada. O PSP consome por `PullNotifications` e mantém seu progresso por cursor opaco autenticado. Não existem ACK individual, lease, `IN_FLIGHT`, retry ativo de delivery nem stream push.

## Áreas a explorar

- PostgreSQL indisponível, restart ou perda de conexão;
- Kafka indisponível, restart, falha de publicação, rebalance e redelivery de mensagens de ingresso;
- crash do SPI antes ou depois do commit financeiro e durante o handoff da outbox para Kafka;
- restart do Notification Gateway com o buffer em memória vazio e recuperação pelo log Kafka;
- PSP offline, reconnect e reapresentação do último cursor durável;
- queda do PSP antes e depois de persistir um lote e seu cursor;
- cursor antigo, expirado, adulterado ou pertencente a outro PSP;
- interrupções de rede entre componentes quando houver hipótese concreta.

Replay funcional de PACS.008 e PACS.002 continua pertencendo à matriz do load-tool. Broker-induced redelivery e falhas de infraestrutura pertencem a esta task.

## Invariantes

- nenhuma obrigação aceita desaparece silenciosamente;
- não ocorre dupla reserva, liquidação, débito ou crédito;
- estado, saldo, auditoria e outbox permanecem atomicamente consistentes;
- replay e redelivery não criam novas obrigações lógicas;
- o PSP pode receber novamente um lote quando reapresenta o cursor anterior;
- restart do Gateway não perde notificações ainda cobertas pela retenção do Kafka;
- mensagens recuperáveis não vão indevidamente para DLQ;
- depois da recuperação, o sistema converge para um estado consistente ou falha explicitamente.

## Evidências

Cada cenário deve correlacionar a falha com pagamentos, transições, auditoria, outbox, offsets/cursor e outcomes observados. A coleta deve ser definida por cenário e não reintroduzir o antigo lifecycle de ACK/lease.

## Fora desta task

- tuning geral ou aumento arbitrário de recursos;
- replay funcional sem falha operacional;
- disponibilidade multi-instância sem injeção de falha;
- recuperação de cursor depois da janela contratada de retenção, que exige procedimento operacional separado.
