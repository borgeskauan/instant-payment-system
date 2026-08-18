# A/B de saldo por participante com reserva no PACS.008

## Objetivo

Comparar a arquitetura atual de liquidez em 16 buckets com a arquitetura de
saldo único por participante e reserva implícita no `pacs.008`, mantendo
workload, recursos e concorrência constantes.

O experimento mede se mover o débito do pagador para o ingresso reduz a
contenção e o custo do settlement sem trocar correção funcional por throughput.
Ele não aprova antecipadamente a nova arquitetura nem a meta final de 2.000 TPS.

## Fonte do comportamento

O comportamento funcional do B é definido por
[`Saldo por participante com reserva implícita no pagamento`](../../architecture/reservation-based-participant-balance.md).
Esta spec não redefine essa arquitetura; ela fixa somente o recorte de
implementação e comparação necessário para o A/B.

## Variantes

### A — buckets

- revisão: `d1483be95fafac23dfd8c631e33b141249c5047b`;
- bundle: `load-test/results/postgres-lock-wait-attribution/20260816_201657`;
- 16 rows de `funds_bucket_entity` por participante;
- saldo debitado e creditado durante o `pacs.002` aceito;
- concorrência dos dois listeners SPI igual a `3`;
- PostgreSQL limitado a 1 vCPU e à memória atual do Compose;
- perfil `mixed-outcomes-2k-diagnostic`, replays de 5% e diagnósticos atuais.

O A permanece imutável. Ele não será executado novamente.

### B — saldo único com reserva

O B parte do commit do A e altera somente a representação e a invariável
financeira:

- uma row de saldo disponível por ISPB;
- `pacs.008` novo reserva no pagador antes de criar `WAITING_ACCEPTANCE`;
- insuficiência no ingresso persiste `REJECTED / INSUFFICIENT_FUNDS` e gera
  `RJCT / AM04`, sem acceptance request;
- `pacs.002 ACCEPTED` credita somente o recebedor;
- `pacs.002 REJECTED` libera somente o pagador;
- replay idêntico não reserva, credita ou libera novamente;
- duplicata divergente continua rejeitada.

Não haverá feature flag nem dois modelos de saldo coexistindo. O commit B é uma
substituição completa e experimental, mantida em branch própria.

## Persistência e reset

O A/B usa ambiente local descartável. A estratégia de migração do MVP é reset:

1. uma nova migração Flyway remove `funds_bucket_entity`;
2. ela cria `participant_balance_entity` com `bank_code` como chave primária e
   `balance_cents BIGINT NOT NULL CHECK (balance_cents >= 0)`;
3. o preparador recria os volumes antes de qualificar o B;
4. nenhum estado de buckets ou pagamento `WAITING_ACCEPTANCE` é migrado.

Não será implementado backfill. Essa estratégia é válida apenas para o
experimento e para ambientes locais explicitamente resetados; uma eventual
adoção fora desse contexto exige plano de migração próprio.

## Processamento do PACS.008

Depois de autenticação e classificação de duplicatas, o B considera apenas
pagamentos logicamente novos. A população que pode contribuir para a reserva é
formada exclusivamente pelas rows que a transação corrente conseguiu
estabelecer como novas, por exemplo pelo resultado de
`INSERT ... ON CONFLICT DO NOTHING RETURNING`. Records recebidos ou tentativas
de insert que perderam o conflito não entram no delta.

Pagadores são bloqueados em ordem de ISPB. Dentro de cada pagador, os pagamentos
são avaliados por `sourceOrdinal`.

Não existe regra de prefixo: uma transação que não cabe é rejeitada, mas uma
transação menor posterior ainda pode consumir o saldo restante. A mutação
física é agregada por pagador, enquanto os outcomes permanecem individuais.

Na mesma transação Spring devem ocorrer:

- débito agregado do saldo disponível;
- insert do pagamento com `WAITING_ACCEPTANCE` ou `REJECTED`;
- auditoria correspondente;
- outbox de acceptance para reservado ou `RJCT / AM04` para insuficiente.

Replay idêntico de pagamento já persistido é no-op. Ele não recria a obrigação
de acceptance e não toca saldo. Dois ingressos idênticos concorrentes produzem
uma única aquisição de criação e, portanto, uma única reserva.

## Processamento do PACS.002

O status original só altera pagamentos ainda em `WAITING_ACCEPTANCE`. Somente
rows cuja transição a partir de `WAITING_ACCEPTANCE` foi efetivamente adquirida
e aplicada pela transação corrente podem contribuir para os deltas agregados.
O conjunto deve vir do resultado guardado da transição sob lock — por exemplo,
`UPDATE ... WHERE status = 'WAITING_ACCEPTANCE' RETURNING` — e nunca diretamente
dos records Kafka recebidos ou de uma leitura anterior ao lock.

- Aceites são agrupados por recebedor e produzem um crédito agregado por ISPB.
- Rejeições são agrupadas por pagador e devolvem a reserva agregada por ISPB.
- As rows de saldo necessárias são bloqueadas em ordem determinística.
- Aceite e rejeição podem coexistir na mesma transação, mas conservam
  populações e deltas separados.
- O pagador nunca é debitado novamente no aceite.

Status repetido ou já terminal não cria nova transição, mutação de saldo,
auditoria ou obrigação lógica. Status divergente continua classificado como
divergente.

Essa exclusão mútua é obrigatória mesmo com uma única instância do SPI: a
configuração atual de concorrência `3` do `ConcurrentMessageListenerContainer`
permite que child containers/consumer threads processem replays simultaneamente.

## Atomicidade e auditoria

`WAITING_ACCEPTANCE` implica reserva confirmada e uma reserva confirmada implica
um pagamento em `WAITING_ACCEPTANCE`. Falha de insert de auditoria ou outbox
reverte saldo e status na mesma transação.

`SETTLEMENT_APPLIED` continua representando o settlement lógico quando um
`pacs.002 ACCEPTED` é aplicado. Ele não significa que o pagador foi debitado
nessa fase; o débito físico ocorreu na reserva.

## Testes funcionais obrigatórios

- provisionamento cria exatamente uma row por participante;
- reset e preserve do provisionamento mantêm o contrato administrativo atual;
- saldo zero rejeita no ingresso com `INSUFFICIENT_FUNDS`;
- pagamento insuficiente não cria acceptance request;
- pagamento menor posterior reserva depois de uma rejeição por saldo;
- reservas reduzem o saldo do pagador pelo agregado correto;
- replay de `pacs.008` não reserva duas vezes;
- dois `pacs.008` idênticos concorrentes produzem exatamente uma reserva e uma
  criação lógica;
- aceite credita recebedores pelo agregado e não altera pagadores;
- rejeição libera pagadores pelo agregado;
- replay de aceite não credita duas vezes;
- replay de rejeição não libera duas vezes;
- dois `pacs.002` idênticos concorrentes produzem exatamente um crédito ou uma
  liberação e uma única transição lógica;
- batch misto mantém deltas e transições corretos;
- auditoria ou outbox com falha desfaz saldo e status;
- locking concorrente na mesma row preserva saldo não negativo;
- duplicatas divergentes continuam rejeitadas;
- smoke `mixed-outcomes-smoke` qualifica ACSC, RJCT/AM04 e replays.

## Protocolo experimental

1. implementar e verificar o B em branch filha de `d1483be`;
2. recriar o ambiente uma vez com o preparador existente;
3. aceitar até três tentativas de smoke apenas conforme a política já embutida
   no preparador;
4. executar exatamente uma vez `mixed-outcomes-2k-diagnostic` com tag
   `reservation-balance-diagnostic`;
5. não executar o perfil de 15 minutos;
6. se o runner terminar com exit `0` ou `1` e bundle completo, analisar o B;
7. se terminar com exit operacional `2` ou bundle incompleto, registrar falha
   operacional e não repetir automaticamente;
8. verificar quiescência imediatamente e, se necessário, uma única vez depois
   do drain natural, sem nova carga.

O B preserva os mesmos recursos, concorrência, batches, tópicos, partições,
HTTP, perfil, replays, diagnósticos e deadlines do A.

## Evidência comparada

Usar as janelas ativas semiabertas próprias de cada bundle e registrar:

- originais ativos iniciados, HTTP 2xx, timeouts e rolling mínimo/máximo;
- `pacs.002` ativos e totais iniciados/aceitos;
- outcomes matched, missing e contradictory;
- p50, p95, p99 e máximo de latência;
- violações de replay `pacs.008` e `pacs.002`;
- custo total/médio/máximo e rows/calls das queries financeiras novas;
- custo normalizado por `pacs.002` aceito;
- waits ativos por relação e tipo;
- CPU média/máxima do PostgreSQL;
- lag imediato e quiescência posterior.

O desaparecimento de `funds_bucket_entity` não é, sozinho, melhoria. O trabalho
pode apenas ter migrado para `participant_balance_entity` ou para o ingresso.

## Decisão predefinida

- `KEEP`: correção funcional permanece íntegra e há direção coerente de melhora
  em trabalho útil end-to-end — capacidade de status/outcome, latência ou custo
  financeiro normalizado — sem regressão no ingresso ativo.
- `DISCARD`: qualquer contradição funcional, replay inválido, saldo incorreto,
  regressão de ingresso ou redução de trabalho útil invalida o B, mesmo que uma
  query isolada fique mais barata.
- `INCONCLUSIVE`: sinais pequenos, ruidosos ou contraditórios não autorizam
  manter a arquitetura nem executar a run de 15 minutos.

Não há limiar percentual arbitrário. A decisão exige coerência entre ingresso,
status, outcomes, latência, custo PostgreSQL e drenagem.

## Política Git

O B será commitado somente na branch `reservation-balance-ab`. Não será feito
merge, push ou alteração da branch `estabilizing-performance`. Se o resultado
for `DISCARD` ou `INCONCLUSIVE`, o commit continua preservado apenas como
experimento reproduzível.

## Fora de escopo

- backfill de ambiente existente;
- timeout ou expiração de reserva;
- fila de liquidez;
- nova tabela/status de reserva;
- nova outbox ou estágio de compensação;
- mudança de recursos, concorrência, batch, Kafka, HTTP ou replay;
- escala horizontal do SPI;
- tuning adicional;
- run de 15 minutos ou aprovação final de 2.000 TPS.
