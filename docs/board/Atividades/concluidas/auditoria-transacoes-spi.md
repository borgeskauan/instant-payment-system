# Auditoria de negócio das transações no SPI

- [x] Auditoria de negócio das transações no SPI

## Objetivo do MVP

A auditoria responde, para cada pagamento, qual resultado de negócio foi
confirmado, por que ele ocorreu e quais efeitos financeiros foram aplicados sem
duplicidade. Ela não replica tentativas técnicas nem o estado interno dos
workers.

`payment_transaction_entity` continua sendo o estado operacional mutável. A
auditoria é append-only pelo comportamento da aplicação, mas não é event
sourcing, mecanismo de recuperação, extrato completo dos participantes nem
registro regulatório inviolável.

## Fatos persistidos

O SPI persiste somente três fatos consolidados:

| Fato confirmado | Evento | Efeito financeiro auditado |
| --- | --- | --- |
| Pagamento admitido e fundos tornados indisponíveis | `PAYMENT_RESERVED` | `sender_delta_cents = -amount_cents` |
| Aceite aplicado e recebedor creditado | `PAYMENT_SETTLED` | `receiver_delta_cents = amount_cents` |
| Pagamento rejeitado na admissão | `PAYMENT_REJECTED` | nenhum delta |
| Pagamento reservado posteriormente rejeitado | `PAYMENT_REJECTED` | `sender_delta_cents = amount_cents` |

Os históricos possíveis no MVP são:

```text
happy path:
PAYMENT_RESERVED → PAYMENT_SETTLED

insufficient funds:
PAYMENT_REJECTED

rejeição depois da reserva:
PAYMENT_RESERVED → PAYMENT_REJECTED
```

Não existe `PAYMENT_CREATED`, `PAYMENT_STATUS_CHANGED` ou
`SETTLEMENT_APPLIED` no contrato vigente. Criação e reserva são um único fato
porque fazem commit atomicamente. Aceite e settlement também são inseparáveis
no modelo atual. A rejeição posterior já descreve a liberação atômica da
reserva.

Se futuramente o domínio permitir aceite confirmado sem settlement, ou
rejeição confirmada sem liberação imediata, esses fatos deverão voltar a ser
separados.

## Formato dos eventos

`payment_audit_event` mantém colunas tipadas, sem `JSONB`:

- `event_id BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL` como identidade
  técnica, sem PK nem garantia de ordem causal;
- `payment_id TEXT NOT NULL`;
- `event_type payment_audit_event_type NOT NULL`;
- `previous_status payment_status`;
- `resulting_status payment_status`;
- `amount_cents BIGINT`;
- `sender_ispb TEXT`;
- `receiver_ispb TEXT`;
- `sender_delta_cents BIGINT`;
- `receiver_delta_cents BIGINT`;
- `reason payment_rejection_reason`;
- `occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.

Constraints validam o formato de cada um dos três eventos. Dois índices
parciais protegem as invariantes:

- `uq_payment_audit_admission`: no máximo um resultado inicial por pagamento;
- `uq_payment_audit_terminal_outcome`: no máximo um resultado terminal para um
  pagamento reservado.

Assim, settlement e rejeição terminal não podem coexistir, e replays não podem
duplicar reserva, crédito ou liberação.

## Gravação transacional e bulk

Cada batch executa statements separados na mesma transação Spring:

1. classifica e aplica pagamento, status e saldos em bulk;
2. insere em bulk os fatos de auditoria efetivamente confirmados;
3. insere em bulk as obrigações de notificação;
4. confirma a transação PostgreSQL.

Falha da auditoria ou da outbox desfaz a reserva, o crédito, a liberação e o
status correspondente. O repositório executa no máximo um insert bulk de
auditoria por fase e não usa `ON CONFLICT`: uma classificação contraditória
falha visivelmente e provoca rollback.

As próprias listas de pagamentos reservados, liquidados e rejeitados já
carregam todos os dados necessários. O DTO intermediário
`PaymentStatusTransition` foi removido e não há releitura do pagamento para
reconstruir a auditoria.

## Replay

- Replay que efetivamente aplica um fato produz o evento normal desse fato.
- Replay idêntico ou concorrente que se torna `NOOP` não produz evento.
- Não existe `PAYMENT_REPLAYED`, evento de retry ou evento de redelivery.
- As constraints e a aquisição transacional da mudança financeira impedem
  efeitos lógicos duplicados.

## Migração do modelo anterior

A V17 não reinterpreta eventos antigos segundo a arquitetura atual. Fazer isso
atribuiria o débito da reserva ao timestamp de uma criação ocorrida antes de a
reserva existir e perderia transições legítimas como `ACCEPTED_IN_PROCESS`.

Por isso, a migration renomeia a tabela anterior para
`payment_audit_event_legacy_v16`, preservando rows, tipos textuais, deltas e
timestamps sem alteração. Uma nova `payment_audit_event`, restrita aos três
fatos consolidados, recebe somente eventos produzidos depois da V17. A view
read-only `payment_audit_event_history` une os dois modelos quando a consulta
histórica completa for necessária. O `event_id` novo continua depois do maior
valor legado, sem transformar essa identidade técnica em ordem causal.

## Fora de escopo

- payload PACS original e tentativas que fizeram rollback;
- Kafka, retries, DLQ, comunicação e entrega de notificações;
- snapshots de saldo, reconstrução integral de contas ou event sourcing;
- ator responsável, credenciais e segredos;
- assinatura, hash chain, WORM ou separação regulatória de privilégios;
- retenção, particionamento, arquivamento, consulta online e UI;
- rejeições de entrada anteriores à criação de um pagamento.

Esses limites preservam o objetivo do MVP: provar o ciclo de vida e os efeitos
financeiros de cada pagamento confirmado, sem transformar a auditoria em uma
segunda implementação do SPI.
