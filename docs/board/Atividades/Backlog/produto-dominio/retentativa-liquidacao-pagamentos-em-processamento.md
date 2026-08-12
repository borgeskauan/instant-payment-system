# Retentativa de liquidação de pagamentos em processamento

- [ ] Retentar a liquidação de pagamentos em `ACCEPTED_IN_PROCESS`

# Contexto

Quando o PSP recebedor aceita um pagamento, o SPI tenta liquidá-lo imediatamente. Se as contas de liquidação ainda não existirem ou o bucket do pagador não tiver saldo suficiente, o pagamento muda de `WAITING_ACCEPTANCE` para `ACCEPTED_IN_PROCESS`.

Hoje esse estado não possui um responsável por continuar o processamento:

- o SPI não possui worker de retentativa de liquidação;
- provisionar ou adicionar fundos não dispara uma nova tentativa;
- replay do mesmo `pacs.002` em `ACCEPTED_IN_PROCESS` é `NOOP`;
- o pagamento pode permanecer indefinidamente nesse estado.

Esse comportamento foi confirmado durante o teste manual do pagamento `fed25d92-b4a1-4ec0-a510-31510ac149af`: a aceitação foi entregue e confirmada pelo PSP recebedor, mas a ausência dos buckets de liquidação levou o pagamento a `ACCEPTED_IN_PROCESS`. O provisionamento posterior das contas não retomou a liquidação.

# Objetivo

Garantir que um pagamento aceito e aguardando liquidez volte a ser considerado para liquidação sem depender de redelivery Kafka, bloqueio da partição ou intervenção manual por pagamento.

Quando houver fundos disponíveis, a recuperação deverá aplicar atomicamente:

```text
ACCEPTED_IN_PROCESS → ACCEPTED_AND_SETTLED
+ débito do pagador
+ crédito do recebedor
+ PAYMENT_STATUS_CHANGED
+ SETTLEMENT_APPLIED
+ notificações ACSC e ACCC na outbox
```

# Direção proposta

Usar um worker interno do SPI para buscar pagamentos em `ACCEPTED_IN_PROCESS` e tentar novamente a liquidação em batches.

O estado operacional do pagamento é a obrigação durável de continuar tentando. O worker não deve depender da mensagem Kafka original, que já foi processada e ACKada corretamente.

Se os fundos continuarem indisponíveis:

- o pagamento permanece em `ACCEPTED_IN_PROCESS`;
- saldos não são alterados;
- nenhum novo evento de negócio é criado;
- nenhuma nova obrigação de notificação é criada.

Uma tentativa que finalmente liquidar o pagamento produz os eventos e obrigações normais da liquidação. Não deve existir um evento especial apenas por a liquidação ter sido retomada por retry.

# DLQ e retry Kafka

Falta de liquidez é uma condição válida de negócio, não uma mensagem inválida ou uma falha técnica de consumo.

Por isso, esta recuperação não deve:

- manter o input Kafka sem ACK enquanto espera por fundos;
- bloquear a partição com retries do consumer;
- enviar o `pacs.002` para DLQ apenas por falta de liquidez;
- depender de redelivery para retomar a liquidação.

A DLQ continua reservada para mensagens inválidas ou falhas de processamento que não possam seguir o fluxo normal definido.

# Garantias

- pagamentos em `ACCEPTED_IN_PROCESS` permanecem recuperáveis após reinício do SPI;
- duas instâncias não podem debitar ou creditar o mesmo pagamento duas vezes;
- a transição de status, os dois movimentos de saldo, os dois eventos de auditoria e as duas rows da outbox commitam ou fazem rollback juntos;
- a auditoria registra o estado anterior real como `ACCEPTED_IN_PROCESS`, sem assumir `WAITING_ACCEPTANCE`;
- falha na auditoria ou na outbox desfaz toda a tentativa de liquidação;
- retry sem fundos não gera histórico de negócio enganoso nem notificações duplicadas;
- pagamentos já liquidados são `NOOP` e não podem ser reabertos.

# Decisões técnicas pendentes

- intervalo, tamanho do batch e política de retry;
- uso de seleção simples concorrente, row locks, `SKIP LOCKED`, claim ou lease;
- necessidade de `next_attempt_at`, contagem de tentativas e último erro;
- distinção operacional entre falta de liquidez e falha técnica da tentativa;
- gatilho adicional ao provisionar fundos, além do scheduler;
- possibilidade de um replay de `pacs.002` solicitar uma tentativa imediata usando a mesma lógica;
- política para pagamentos que permaneçam sem liquidez por muito tempo;
- necessidade de estado terminal, intervenção operacional ou expiração definida pelo negócio;
- métricas e alertas para quantidade e idade dos pagamentos pendentes.

As decisões de concorrência devem considerar que, diferente da publicação de uma notificação duplicável, uma liquidação duplicada altera saldos e não pode ser aceita.

# Critérios de aceite

- falta de fundos move o pagamento uma única vez para `ACCEPTED_IN_PROCESS`;
- provisionar fundos permite que uma tentativa posterior conclua a liquidação;
- a recuperação acontece sem uma nova mensagem Kafka obrigatória;
- reiniciar o SPI não perde pagamentos aguardando liquidação;
- concorrência entre workers ou instâncias não produz débito ou crédito duplicado;
- falta contínua de fundos mantém status, saldos, auditoria e outbox inalterados;
- sucesso muda o status para `ACCEPTED_AND_SETTLED` e aplica exatamente um débito e um crédito;
- sucesso cria `PAYMENT_STATUS_CHANGED` de `ACCEPTED_IN_PROCESS` para `ACCEPTED_AND_SETTLED` e um `SETTLEMENT_APPLIED`;
- os dois eventos da liquidação são atômicos e não possuem requisito de ordem por `event_id`;
- sucesso cria uma notificação `ACSC` para o pagador e uma `ACCC` para o recebedor na outbox;
- falha no insert da auditoria ou da outbox provoca rollback de status e saldos;
- replay e execução concorrente depois do settlement são `NOOP`;
- testes de integração usam PostgreSQL real e verificam diretamente status, saldos, auditoria e outbox;
- um cenário manual confirma a evolução completa antes do fechamento da task.

# Limitações Conscientes

- a política de expiração ou encerramento por falta prolongada de liquidez ainda não está definida;
- retries técnicos e tentativas sem efeito não pertencem à auditoria de negócio existente;
- uma solução baseada apenas em polling pode adicionar carga ao PostgreSQL;
- o endpoint administrativo de fundos é uma ferramenta local e não representa o processo real de aporte de liquidez em produção.

# Sinais para Evolução

- crescimento contínuo da quantidade ou idade de pagamentos em `ACCEPTED_IN_PROCESS`;
- contenção relevante entre workers tentando as mesmas rows;
- polling frequente causar carga perceptível no PostgreSQL;
- necessidade operacional de prioridade, expiração ou intervenção manual;
- necessidade de distinguir falta de liquidez, conta inexistente e indisponibilidade técnica;
- requisitos regulatórios definirem prazo ou resultado terminal para pagamentos não liquidados.
