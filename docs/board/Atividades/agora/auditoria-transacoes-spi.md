# Auditoria completa das transações no SPI sem pesar o hot path

- [ ] Auditoria completa das transações no SPI sem pesar o hot path

**Por que existe**

Para reduzir pressão no PostgreSQL durante os testes de carga, o SPI passou a persistir no caminho quente apenas os campos necessários para liquidação e roteamento da confirmação: `payment_id`, `amount`, `status`, `sender_bank_code` e `receiver_bank_code`.

Isso ajuda a medir o impacto de uma tabela operacional estreita, mas não resolve a necessidade real de auditoria. Em produção, o sistema precisa conseguir reconstituir a transação completa, incluindo payload recebido, dados das partes e metadados de origem. Essa auditoria não deve voltar a bloquear a liquidação dentro do SLA.

Além disso, a tabela operacional `payment_transaction_entity` não possui timestamps. Hoje o SPI não consegue determinar pelo estado persistido quando o pagamento foi criado, quando ocorreu a última alteração ou há quanto tempo ele permanece no status atual. Esses dados são necessários para timeout, reconciliação, investigação operacional e medição de latência, mesmo que o histórico completo seja mantido separadamente.

**Tarefas**

- [ ] Definir o modelo de auditoria completo da transação.
- [ ] Decidir se a fonte auditável principal será Kafka, tabela dedicada, outbox ou combinação desses mecanismos.
- [ ] Criar fluxo assíncrono para persistir payload/dados completos sem bloquear o settlement.
- [ ] Registrar metadados de origem: tópico, partição, offset, timestamp de consumo e identificador fim a fim.
- [ ] Adicionar timestamps operacionais `created_at`, `updated_at` e `status_changed_at` com tipo `TIMESTAMPTZ` em `payment_transaction_entity`.
- [ ] Atualizar explicitamente `updated_at` e `status_changed_at` nas queries bulk JDBC que alteram o status; callbacks do JPA não cobrem esses updates.
- [ ] Definir a semântica de backfill dos timestamps para transações legadas, sem apresentar a data da migration como data histórica real.
- [ ] Manter claro que os timestamps da row representam o estado operacional atual e não substituem um histórico auditável de transições.
- [ ] Manter tabela operacional estreita para o caminho quente de liquidação.
- [ ] Definir política de retenção e consulta para dados de auditoria.
- [ ] Medir o impacto da auditoria assíncrona no load test antes de considerá-la parte do fluxo padrão.
