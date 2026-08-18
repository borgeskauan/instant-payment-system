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

## Resultado da implementação experimental

A variante B foi implementada e verificada na branch isolada
`reservation-balance-ab`; a arquitetura não foi adotada. O código experimental
possui:

- migration reset-only para `participant_balance_entity` com uma row por ISPB;
- reserva bulk por pagador somente para pagamentos efetivamente inseridos;
- rejeição no ingresso com `REJECTED / INSUFFICIENT_FUNDS`, audit de criação e
  outbox `RJCT / AM04` na mesma transação;
- aceite que credita somente o recebedor e rejeição que libera somente o
  pagador;
- locks de pagamentos e participantes em ordem determinística;
- deltas derivados exclusivamente das transições guardadas que a transação
  corrente efetivamente aplicou;
- testes concorrentes provando uma única reserva, crédito ou liberação sob
  replay idêntico;
- rollback conjunto de saldo, status, audit e outbox.

O smoke funcional qualificou os `1.250` pagamentos, com `1.000` ACSC e `250`
RJCT/AM04, após corrigir somente uma expectativa legada do qualificador: um
pagamento rejeitado por saldo no ingresso não inicia um PACS.002 do recebedor.

A única medição curta B foi
`reservation-balance-diagnostic/20260816_221950`. A decisão predefinida foi
**DISCARD**:

- PACS.002 happy-path ativos aceitos caíram de `32.611` no A para `28.393` no B
  (`-12,9%`);
- a query que bloqueia a row única do participante acumulou `79.564,414 ms` e
  chegou a `28.904,898 ms` em uma chamada;
- `10` dos `11` lock waits nativos maiores que um segundo estavam no lock de
  `participant_balance_entity`; o outro estava em
  `payment_transaction_entity`;
- o piso rolling do ingresso caiu de `1.943` para `459/s`, apesar do catch-up
  elevar a média e o total de originais ativos;
- um replay PACS.002 teve timeout, contra zero violações no A.

O desenho eliminou o two-account settlement e reduziu o custo SQL financeiro
observado por PACS.002 aceito, mas trocou o striping de 16 buckets por
serialização na única row do participante. Sob o hot-pair workload, essa nova
contenção reduziu trabalho útil e violou o gate de replay. Não deve haver merge
da branch experimental nem run de 15 minutos. Uma eventual nova proposta deve
separar a semântica de liquidez da estratégia de contenção; não basta retomar
esta substituição tal como implementada.

Um A/B posterior aplicou somente `key = authenticated ISPB` nos dois tópicos de
ingresso, mantendo toda a arquitetura e o workload. O run keyed
`reservation-balance-kafka-key-diagnostic/20260817_234042` eliminou os waits
nativos maiores que um segundo no saldo (`10 → 0`), reduziu a máxima da query
de lock de `28.904,898` para `91,820 ms`, zerou o status lag e elevou as
transições aplicadas de `7.640` para `23.470`.

Mesmo assim, a configuração também foi **DISCARD**. O hash das dez hot keys e
a atribuição das oito partições aos três consumers distribuíram o tópico de
pagamentos em `51,46% / 37,03% / 11,51%`. O lag imediato de pagamentos subiu
para `75.619`, PACS.002 ativos aceitos caíram de `28.393` para `18.474`, o piso
rolling caiu a zero e os replays tiveram `32 / 5` violações. A única leitura
posterior ficou quiescente e nenhum run de 15 minutos ocorreu.

A evidência separa as hipóteses: afinidade por ISPB resolve a disputa da row no
settlement, mas a distribuição atual das hot keys cria um gargalo por consumer
no ingresso. Uma nova tentativa não deve reintroduzir buckets nem adicionar
lanes em memória antes de medir uma estratégia Kafka que preserve afinidade e
distribua os participantes quentes de forma compatível com a concorrência.

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
