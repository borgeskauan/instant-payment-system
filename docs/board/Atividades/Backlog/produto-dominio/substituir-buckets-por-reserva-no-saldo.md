# Substituir buckets por reserva no saldo do participante

- [ ] Migrar a liquidação do SPI para saldo único por participante com reserva no `pacs.008`

## Contexto

O SPI fragmenta a liquidez de cada participante em 16 buckets escolhidos pelo
hash do pagamento e só movimenta os saldos durante o `pacs.002`. Isso cria falta
de liquidez artificial por bucket e obriga o settlement a bloquear pagador e
recebedor na mesma operação.

O desenho detalhado está em
[`Saldo por participante com reserva implícita no pagamento`](../../../../architecture/reservation-based-participant-balance.md).
Ele é a fonte do comportamento esperado e das limitações deliberadas desta task.

## Objetivo

Substituir os buckets por uma row de saldo disponível por ISPB e dividir o fluxo
financeiro em operações de um único participante:

- `pacs.008`: reservar no pagador ou rejeitar imediatamente com
  `INSUFFICIENT_FUNDS / AM04`;
- `pacs.002` aceito: creditar somente o recebedor;
- `pacs.002` rejeitado: devolver a reserva somente ao pagador.

O estado `WAITING_ACCEPTANCE` passa a provar que o valor já saiu do saldo
disponível do pagador, sem tabela ou status específico de reserva.

## Escopo

- remover `funds_bucket_entity`, `bucket_id`, hashing e distribuição em 16
  buckets;
- preservar valores monetários em centavos inteiros;
- processar pagamentos em ordem original por pagador, sem regra de prefixo;
- agregar as mutações físicas por participante mantendo outcomes individuais;
- adquirir locks de saldo em ordem determinística;
- tornar saldo, status, auditoria e outbox atomicamente consistentes;
- adaptar provisionamento administrativo, fixtures e load-tool;
- preservar replay idempotente e rejeição explícita de duplicatas divergentes;
- documentar a estratégia de migração ou reset necessária para estabelecer a
  nova invariável.

## Critérios de aceite

- cada participante possui um único saldo disponível autoritativo;
- `WAITING_ACCEPTANCE` nunca existe sem o débito correspondente da reserva;
- pagamento insuficiente não altera saldo nem cria acceptance request;
- pagamento menor posterior pode usar o saldo restante após uma rejeição;
- aceite credita o recebedor sem tocar novamente o pagador;
- rejeição libera exatamente uma vez o saldo do pagador;
- batches mistos e replays preservam status e saldos corretos;
- falha de auditoria ou outbox faz rollback da mutação financeira e do status;
- locking PostgreSQL mantém correção sob concorrência na mesma row;
- não resta código ou documentação operacional dependente de buckets;
- o smoke funcional do workload misto permanece válido.

## Limitações deliberadas

- uma instância de processamento do SPI é suficiente para o MVP;
- reserva não possui timeout nesta fatia;
- insuficiência rejeita imediatamente, sem fila de liquidez;
- participante sem saldo ainda pode ser tratado como insuficiência até uma
  evolução de integridade/onboarding;
- não serão criadas tabela de reserva, nova outbox ou camada de compensação;
- tuning, escala horizontal e runs longos de performance ficam fora desta task.

## Relação com o backlog

A mudança elimina falta de liquidez descoberta no settlement. Ao priorizar esta
task, reavaliar
[`Retentativa de liquidação de pagamentos em processamento`](retentativa-liquidacao-pagamentos-em-processamento.md),
pois sua motivação atual pode deixar de existir para pagamentos sem fundos.
