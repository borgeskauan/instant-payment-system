# Auditoria de negócio das transações no SPI

- [x] Auditoria de negócio das transações no SPI

## Contexto

Para reduzir pressão no PostgreSQL, o SPI mantém `payment_transaction_entity` estreita e voltada ao estado operacional necessário para liquidação e notificações. Essa row mutável não substitui um histórico append-only capaz de explicar os fatos de negócio efetivamente aplicados.

Os timestamps operacionais `created_at`, `updated_at` e `status_changed_at` continuam sendo uma necessidade separada. Eles descrevem o estado atual e não substituem esta auditoria.

A auditoria de entradas rejeitadas também possui origens e garantias diferentes e permanece separada em [`auditoria-rejeicoes-entrada.md`](../agora/auditoria-rejeicoes-entrada.md).

## Solução implementada

O SPI passa a persistir eventos em `payment_audit_event`:

| Fato efetivamente aplicado | Eventos produzidos |
| --- | --- |
| Novo pagamento | `PAYMENT_CREATED` |
| `WAITING_ACCEPTANCE → REJECTED` | `PAYMENT_STATUS_CHANGED` |
| Aceite sem fundos suficientes: `WAITING_ACCEPTANCE → ACCEPTED_IN_PROCESS` | `PAYMENT_STATUS_CHANGED` |
| Liquidação: `WAITING_ACCEPTANCE → ACCEPTED_AND_SETTLED` | `PAYMENT_STATUS_CHANGED` + `SETTLEMENT_APPLIED` |
| Replay que aplica um dos fatos acima | Os mesmos eventos normais correspondentes |
| Replay, duplicata homogênea ou processamento que resulta em `NOOP` | Nenhum evento |
| Entrada divergente ou não autorizada | Fora desta task |

A criação produz somente `PAYMENT_CREATED`; não existe uma mudança de status adicional para o estado inicial. `PAYMENT_STATUS_CHANGED` e `SETTLEMENT_APPLIED` são fatos separados, porém uma liquidação persiste ambos no mesmo bulk e na mesma transação. Ou os dois existem, ou nenhum existe.

Não existe requisito de ordem entre esses dois eventos. `event_id` é apenas identidade técnica e não representa uma sequência causal do pagamento.

## Modelo físico

`payment_audit_event` possui colunas tipadas, sem `JSONB`:

- `event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`;
- `payment_id TEXT NOT NULL`;
- `event_type TEXT NOT NULL`;
- `previous_status TEXT`;
- `resulting_status TEXT`;
- `amount_cents BIGINT`;
- `sender_ispb TEXT`;
- `receiver_ispb TEXT`;
- `sender_delta_cents BIGINT`;
- `receiver_delta_cents BIGINT`;
- `occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.

Constraints permitem somente `PAYMENT_CREATED`, `PAYMENT_STATUS_CHANGED` e `SETTLEMENT_APPLIED` e exigem o formato correspondente de cada evento. Criação e settlement são únicos por `payment_id` no modelo atual.

Não existe unicidade em `payment_id + previous_status + resulting_status`. A auditoria registra toda transição efetivamente aplicada e permite que a mesma transição legítima volte a ocorrer caso a máquina de estados evolua.

Os índices são:

- `(payment_id, event_id)` para consulta do histórico;
- único parcial por `payment_id` para `PAYMENT_CREATED`;
- único parcial por `payment_id` para `SETTLEMENT_APPLIED`.

Não existe foreign key para `payment_transaction_entity`. Valor, ISPB do pagador e ISPB do recebedor são preservados nos eventos que precisam explicar esses fatos sem depender da row operacional. O payload PACS original, saldos anteriores e posteriores, credenciais e segredos não são armazenados.

## Gravação transacional e bulk

Cada batch executa statements separados dentro do mesmo `@Transactional`:

1. classificar e aplicar o statement financeiro bulk;
2. inserir em bulk os eventos de auditoria dos resultados efetivos;
3. inserir em bulk as obrigações de notificação correspondentes;
4. fazer commit PostgreSQL.

Atomicidade não depende de um único CTE. Falha da auditoria ou da outbox desfaz pagamento, status e saldos. A exceção de banco não é embrulhada; indisponibilidade de recurso continua chegando ao consumer como falha de infraestrutura, sem ACK prematuro do input Kafka.

O statement financeiro retorna somente os fatos que já classificou:

- `createdPayments` distingue rows realmente inseridas de acceptance replays;
- `appliedStatusTransitions` inclui settlement, rejeição e a transição antes oculta para `ACCEPTED_IN_PROCESS`;
- as classificações existentes para outbox, divergência e autorização são preservadas.

Não há releitura de `payment_transaction_entity` para reconstruir eventos. O serviço monta uma lista plana e o repositório executa no máximo um insert bulk de auditoria por batch. O insert não usa `ON CONFLICT`: uma violação inesperada falha visivelmente e provoca rollback, em vez de ocultar erro de classificação.

## Replay

- Replay que realmente cria o pagamento produz `PAYMENT_CREATED`.
- Replay que realmente aplica transição ou liquidação produz os eventos normais correspondentes.
- Replay idêntico de `pacs.008` em `WAITING_ACCEPTANCE` pode preservar ou recriar a outbox, mas não recria o pagamento e não produz outro `PAYMENT_CREATED`.
- Replay em estado avançado e `pacs.002` que não aplica nova transição ou settlement são `NOOP` e não produzem auditoria.
- Não existe `PAYMENT_REPLAYED` neste corte.

## Critérios de aceite

- criação, auditoria e acceptance outbox commitam ou fazem rollback juntas;
- rejeição, auditoria e rejection outbox commitam ou fazem rollback juntas;
- settlement, saldos, status, dois eventos de auditoria e duas obrigações commitam ou fazem rollback juntos;
- falta de fundos registra somente a transição efetivamente aplicada;
- falha no insert de auditoria desfaz o fato financeiro antes de iniciar o trabalho da outbox;
- indisponibilidade do PostgreSQL na auditoria preserva a exceção de infraestrutura;
- inserts continuam bulk e não existe operação por item;
- replay `NOOP` não cria evento e replay com efeito produz eventos normais;
- duplicata homogênea no mesmo batch gera no máximo o fato efetivamente aplicado;
- constraints rejeitam tipos e formatos inválidos;
- criação e settlement duplicados são rejeitados;
- a mesma transição de status pode ser persistida mais de uma vez;
- testes não dependem de ordem entre `PAYMENT_STATUS_CHANGED` e `SETTLEMENT_APPLIED`;
- reset do teste de carga limpa a tabela de auditoria quando solicitado.

## Retenção e dados

- O MVP utiliza somente dados sintéticos.
- Não existe backfill: pagamentos anteriores à migration não ganham eventos inventados nem timestamps imprecisos.
- Não existe retenção ou limpeza automática.
- O reset explícito do ambiente de carga pode truncar a tabela entre execuções.
- Particionamento, arquivamento e object storage ficam adiados.

## Validação final

A suíte completa do SPI passou com 180 testes, sem falhas ou erros, usando PostgreSQL 17 real via Testcontainers nos testes de integração.

Os cenários manuais também foram validados diretamente no PostgreSQL:

- `fed25d92-b4a1-4ec0-a510-31510ac149af`: criação seguida de `WAITING_ACCEPTANCE → ACCEPTED_IN_PROCESS` por ausência das contas de liquidação, sem settlement ou alteração de saldos;
- `361b0d2a-39aa-445f-8f8d-8f19a45d5fea`: criação e liquidação imediata, com os três eventos esperados, débito e crédito de `2550` centavos e ACK das três deliveries;
- replay da acceptance do pagamento liquidado: delivery repetida e ACKada sem alterar status, saldos, auditoria ou outbox;
- `4489405a-ce8e-4b20-bfd6-0a59b9b76ae0`: rejeição autenticada pelo PSP recebedor, com `WAITING_ACCEPTANCE → REJECTED`, notificação `RJCT` ACKada e saldos intactos;
- acceptances atrasadas após a rejeição permaneceram `NOOP` e não reabriram nem liquidaram o pagamento.

## Limitações Conscientes

- O histórico começa na implantação da migration e pode ser parcial para pagamentos preexistentes.
- Append-only é uma regra da aplicação; este corte não adiciona trigger ou separação de privilégios para impedir `UPDATE` e `DELETE` administrativos.
- `event_id` é identidade técnica e não oferece ordem causal entre fatos do mesmo pagamento.
- Não existe indicador especial de replay; eventos descrevem o efeito aplicado.
- Replay `NOOP`, retry, redelivery, DLQ e outras tentativas diagnósticas não são auditados.
- Rejeições de entrada pertencem a outra task.
- O payload PACS original, ator do settlement e snapshots de saldo não são armazenados.
- Não existe backfill, retenção, cleanup, particionamento ou arquivamento.
- A tabela crescerá continuamente.
- Este corte não inclui load test comparativo nem métricas específicas de auditoria.
- Essas simplificações são adequadas ao MVP, mas não definem necessariamente o desenho final de produção.

## Sinais para Evolução

Evoluir o desenho quando ocorrer pelo menos uma destas condições:

- crescimento da tabela ou do WAL exigir retenção, particionamento, arquivamento ou cleanup;
- impacto relevante em throughput, latência ou uso do PostgreSQL;
- necessidade de backfill ou reconstrução histórica de pagamentos anteriores à migration;
- exigência de ordem causal explícita ou correlação entre eventos da mesma operação;
- necessidade de distinguir replay, retry, redelivery ou tentativa original;
- necessidade de auditar rejeições, ator responsável ou payload original sanitizado;
- necessidade de impedir updates/deletes por privilégios separados ou proteção adicional no banco;
- consultas operacionais exigirem novos índices ou uma projeção de leitura;
- dados deixarem de ser exclusivamente sintéticos e exigirem política formal de acesso e retenção.
