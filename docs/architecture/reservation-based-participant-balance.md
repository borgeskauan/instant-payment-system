# Saldo por participante com reserva implícita no pagamento

## Decisão

Substituir a liquidez fragmentada em buckets por um único saldo disponível por
participante e antecipar a reserva de fundos para o processamento do `pacs.008`.

O modelo mantém batching, mas cada operação financeira passa a tocar apenas um
participante por fase:

```text
pacs.008           → reserva no pagador
pacs.002 aceito    → crédito no recebedor
pacs.002 rejeitado → liberação no pagador
```

O PostgreSQL continua sendo responsável pela serialização quando operações
concorrentes atingirem o mesmo participante.

## Motivação

O desenho anterior distribuía o saldo de cada participante em 16 rows selecionadas
pelo hash do `payment_id`. A liquidação ocorre somente após o aceite e precisa
travar, na mesma operação, o bucket do pagador e o bucket do recebedor.

Essa fragmentação é sintética: um participante pode possuir saldo total
suficiente e ainda assim falhar por falta de liquidez no bucket escolhido. Ela
também amplia a consulta de settlement, cria rebalanço implícito de liquidez e
faz o `pacs.002` carregar a responsabilidade de debitar e creditar duas contas.

O novo desenho remove essa complexidade. O saldo representa apenas dinheiro
disponível para novos pagamentos e a reserva é representada pelo próprio estado
durável do pagamento.

## Modelo e invariantes

Cada ISPB possui uma única row conceitual:

```text
participant_balance
-------------------
bank_code      primary key
balance_cents  bigint, non-negative
```

Não haverá `reserved_balance`, tabela de reservas ou `reservation_status`.

A reserva obedece à equivalência:

```text
payment.status = WAITING_ACCEPTANCE
⇔
payment.amount_cents já foi removido do saldo disponível do pagador
```

Criação do pagamento, débito da reserva, auditoria e outbox precisam compartilhar
a mesma transação. O banco não pode confirmar apenas um dos lados da
equivalência.

Um pagamento rejeitado por insuficiência no ingresso nunca ocupa reserva e nunca
entra em `WAITING_ACCEPTANCE`.

## Processamento de `pacs.008`

Depois das validações de autenticação, idempotência e duplicidade divergente, a
transação precisa primeiro estabelecer quais pagamentos são realmente novos. O
conjunto usado para calcular reservas deve ser formado somente pelas rows que a
transação corrente conseguiu inserir ou adquirir de forma equivalente. Records
Kafka recebidos, classificação anterior ao lock ou tentativa de insert que
perdeu um `ON CONFLICT` não podem contribuir para o débito agregado.

Os pagamentos efetivamente estabelecidos como novos são então agrupados por
ISPB pagador.

As rows dos pagadores distintos são bloqueadas uma vez, preferencialmente em uma
consulta bulk `FOR UPDATE`, com ISPBs em ordem determinística. Dentro de cada
pagador, os pagamentos são avaliados pela ordem original da mensagem Kafka.

Não existe regra de prefixo. Um pagamento que não cabe é rejeitado, mas não
impede que um pagamento menor e posterior use o saldo restante:

```text
saldo disponível de A = 100

80 → reserva; saldo lógico restante 20
50 → rejeita por insuficiência; restante 20
10 → reserva; restante 10
```

O banco recebe uma mutação física agregada por pagador, enquanto status e efeitos
permanecem individuais por pagamento.

Para cada pagamento reservado:

- inserir o pagamento em `WAITING_ACCEPTANCE`;
- descontar seu valor do saldo disponível do pagador;
- registrar a criação na auditoria;
- criar a obrigação normal de aceite para o recebedor.

Para cada pagamento sem saldo:

- persistir `REJECTED / INSUFFICIENT_FUNDS`;
- gerar a notificação `RJCT` com motivo PACS `AM04` para o pagador;
- não criar acceptance request;
- não criar settlement nem settlement audit;
- não alterar saldo.

Todos os resultados e a mutação agregada do saldo confirmam ou fazem rollback em
conjunto.

Replay idêntico permanece no-op e não reserva novamente. Isso também vale para
dois `pacs.008` idênticos processados concorrentemente: somente a transação que
estabelecer o pagamento como novo pode reservar saldo e criar seus efeitos.
Duplicata divergente continua sendo rejeitada explicitamente.

## Aquisição da transição de `pacs.002`

Somente pagamentos que ainda estejam em `WAITING_ACCEPTANCE` podem consumir o
resultado original. Mais precisamente, crédito ou liberação só pode ser
calculado a partir das rows cuja transição desde `WAITING_ACCEPTANCE` foi
efetivamente adquirida e aplicada pela transação corrente, por exemplo pelo
conjunto devolvido por um update guardado. O delta nunca é derivado diretamente
dos records Kafka recebidos nem de uma leitura anterior ao lock.

## Processamento de `pacs.002` aceito

Os pagamentos aceitos são agrupados pelo ISPB recebedor. O total de cada grupo é
creditado com uma mutação agregada na row do recebedor. O saldo do pagador não é
tocado novamente, pois o valor já saiu da disponibilidade no ingresso.

Crédito no recebedor, transição para o estado final aceito existente, auditoria e
outbox confirmam atomicamente.

Replay do mesmo status não credita novamente e não cria nova transição,
auditoria ou notificação lógica.

## Processamento de `pacs.002` rejeitado

Os pagamentos rejeitados são agrupados pelo ISPB pagador. O valor reservado é
devolvido ao saldo disponível com uma mutação agregada por pagador, e cada
pagamento transiciona de `WAITING_ACCEPTANCE` para `REJECTED`.

Liberação, transição, auditoria e outbox confirmam atomicamente. Replay não
libera o mesmo valor novamente.

Uma rejeição recebida para pagamento que nunca reservou fundos não pode produzir
crédito.

## Batch misto de `pacs.002`

Aceites e rejeições permanecem na mesma transação Spring, mas são calculados em
populações separadas:

```text
pacs.002
├── aceitos   → agrupar e creditar por recebedor
└── rejeitados → agrupar e liberar por pagador
```

Se o mesmo participante aparecer nos dois grupos, o PostgreSQL serializa as
mutações. Não será criado um estágio adicional para compensar ou combinar esses
deltas no MVP.

## Concorrência e contenção aceita

O único conflito financeiro intencional passa a ser entre mutações da mesma row
de participante, por exemplo uma nova reserva concorrendo com uma liberação.

Locks precisam ser adquiridos em ordem determinística para evitar deadlocks
entre batches. A correção concorrente já é requisito do MVP de instância única:
o `ConcurrentMessageListenerContainer` configurado com concorrência `3` cria
múltiplos child containers/consumer threads capazes de processar replays em
paralelo. Portanto, a aquisição exclusiva da criação ou da transição é parte da
correção atual, não apenas proteção para escala horizontal futura.

Escala horizontal, rebalanceamento Kafka e contenção entre réplicas ficam para
trabalho futuro.

## Persistência e migração

- Substituir `funds_bucket_entity` pela representação única por ISPB.
- Manter dinheiro em centavos inteiros, seguindo o contrato atual do SPI.
- Adaptar provisionamento e consulta administrativa para uma row por
  participante.
- Remover hash de `payment_id`, `bucket_id`, `BUCKET_COUNT`, somas de buckets e
  helpers de teste correspondentes.
- Não adicionar persistência específica de reservas.

A implementação experimental escolheu explicitamente a estratégia reset-only.
A migration V10 remove as duas tabelas legadas e cria
`participant_balance_entity(bank_code, balance_cents)`. Não existe backfill nem
coexistência dos dois modelos; o ambiente B precisa ser criado com volumes
limpos para que nenhum `WAITING_ACCEPTANCE` legado viole a nova equivalência.

No `pacs.008`, a fronteira bulk é composta pelo insert que reivindica as rows,
um lock ordenado dos pagadores, um fold determinístico por `sourceOrdinal`, uma
atualização agregada de débitos e uma atualização das rejeições. No `pacs.002`,
a mesma conexão transacional bloqueia pagamentos por `payment_id`, bloqueia os
participantes necessários por ISPB, aplica a transição guardada e só então
calcula os deltas a partir das rows retornadas por essa transição.

## Auditoria e outbox

A auditoria deve continuar descrevendo o resultado lógico do pagamento sem
indicar que o pagador foi debitado novamente no aceite. Se o evento
`SETTLEMENT_APPLIED` continuar representando o settlement lógico completo, essa
semântica deve ser documentada e separada das mutações físicas por fase.

Falha na auditoria ou na outbox precisa desfazer a reserva, o crédito ou a
liberação e a respectiva transição. Cada transição cria no máximo uma obrigação
lógica, preservando a semântica definida para replay idêntico.

Não será criado novo estágio de credit obligation ou outra outbox além do que já
existe.

## Superfícies afetadas

- migrations e schema do SPI;
- `FundsRepository`, `FundsJpaAdapter` e serviço/controlador administrativo;
- persistência bulk de `pacs.008`;
- persistência bulk de `pacs.002`;
- classificação de resultados por pagamento;
- auditoria e testes de rollback transacional;
- provisionamento do load-tool e fixtures de integração;
- documentação que descreve buckets ou falta de liquidez no settlement.

## Testes obrigatórios

- saldo zero rejeita imediatamente com `INSUFFICIENT_FUNDS` e `AM04`;
- financiamento parcial do pagador produz resultados individuais corretos;
- pagamento menor posterior ainda reserva depois de um pagamento que não coube;
- reservas bem-sucedidas reduzem o saldo pelo agregado correto;
- reserva e `WAITING_ACCEPTANCE` fazem commit ou rollback juntos;
- insuficiência não cria acceptance request nem settlement audit;
- aceites creditam recebedores por valores agregados;
- rejeições liberam reservas por valores agregados no pagador;
- aceites e rejeições coexistem corretamente no mesmo batch de `pacs.002`;
- aceite nunca altera novamente o saldo do pagador;
- replay de rejeição não libera duas vezes;
- replay de aceite não credita duas vezes;
- dois `pacs.008` idênticos concorrentes produzem exatamente uma reserva;
- dois `pacs.002` idênticos concorrentes produzem exatamente um crédito ou uma
  liberação, conforme o outcome;
- falha de auditoria ou outbox desfaz saldo e status correspondentes;
- concorrência na mesma row mantém saldo correto sob locking PostgreSQL;
- duplicata divergente continua rejeitada;
- smoke funcional do workload misto continua válido.

## Limitações deliberadas do MVP

- `WAITING_ACCEPTANCE` pode manter liquidez reservada indefinidamente se o
  recebedor nunca responder; timeout e expiração ficam para evolução futura.
- Participante sem row de saldo pode, inicialmente, continuar aparecendo como
  `INSUFFICIENT_FUNDS` no ingresso. Se um recebedor sem row for necessário para
  concluir um `pacs.002`, a transação falha e o batch deve ser repetido; no
  futuro, ambos os casos devem ter uma classificação operacional explícita de
  integridade ou onboarding.
- Não existe fila de liquidez: insuficiência rejeita imediatamente.
- Não existe rebalanceamento porque não existem buckets.
- Não existe entidade ou status próprio de reserva.
- Não existe requisito de performance multi-instância.
- Tuning e runs longos pertencem à estabilização posterior.

## Relação com tarefas existentes

A task `retentativa-liquidacao-pagamentos-em-processamento.md` foi criada para o
modelo no qual a falta de liquidez era descoberta durante o `pacs.002` e podia
deixar pagamentos em `ACCEPTED_IN_PROCESS`.

Com reserva no `pacs.008`, falta de saldo deixa de produzir esse estado. A task
de retentativa deverá ser reavaliada quando esta mudança for priorizada: ela pode
se tornar obsoleta para insuficiência de fundos, embora outros usos futuros de
`ACCEPTED_IN_PROCESS` precisem ser analisados separadamente.
