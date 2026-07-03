# Dead letter queue para mensagens inválidas

- [x] Dead letter queue para mensagens inválidas

**Por que existe**

Mensagens inválidas, incompatíveis com o contrato ou impossíveis de processar não devem travar o consumer group nem contaminar o caminho quente. O fluxo precisa isolar esses casos em uma DLQ com metadados suficientes para diagnóstico e descarte consciente.

**Tarefas**

- [x] Definir política de DLQ para falhas de parse, validação, contrato incompatível e erro inesperado de processamento.
- [x] Criar tópicos DLQ para requests e status reports, com convenção de nomes clara.
- [x] Publicar na DLQ o payload original e metadados: tópico original, partição, offset, timestamp, consumer group, erro, stack resumida e serviço origem.
- [x] Garantir que mensagens enviadas para DLQ não travem o consumer group principal.
- [x] Criar logs estruturados para envio à DLQ sem logar payload sensível completo por padrão.
- [x] Criar teste automatizado para payload inválido: mensagem vai para DLQ, consumer continua e fluxo saudável não é afetado.
- [x] Criar teste automatizado para contrato antigo/incompatível, como PACS bruto chegando no tópico interno protobuf.
- [x] Documentar a política de falhas que envia ou não envia mensagens para DLQ.

**Entregue nesta versão**

- DLQ implementada nos consumers Kafka do SPI.
- Tópicos `spi-payment-requests.dlq` e `spi-payment-status-reports.dlq` criados no `kafka-init`.
- Payload inválido ou protobuf incompatível vai para DLQ por registro e não aborta o restante do batch.
- Erro desconhecido de processamento usa retry curto e depois DLQ.
- Falha clara de infraestrutura, como indisponibilidade do PostgreSQL, não vai para DLQ; o consumer pausa/backoff e reprocessa o mesmo batch quando a infraestrutura volta.
- Ack manual imediato configurado; ack só acontece ao final do batch ou após recuperação confirmada pelo error handler.
- Documentação operacional adicionada em `docs/KAFKA_DLQ_POLICY.md`.

**Fora do escopo desta versão**

- Métricas, dashboards e alertas de DLQ, acompanhados na task de observabilidade operacional.
- Processo operacional ou ferramenta de replay manual a partir da DLQ.

**Próximo risco técnico relacionado**

- Garantias completas de idempotência para reentrega/reprocessamento Kafka, incluindo replay controlado de payload preservado na DLQ como técnica de teste, acompanhadas no backlog de operação e testes.
