# Auditoria de negócio das transações no SPI

- [ ] Auditoria de negócio das transações no SPI

# Contexto

Para reduzir pressão no PostgreSQL durante os testes de carga, o SPI persiste no caminho quente apenas os campos necessários para liquidação e roteamento das notificações. A tabela operacional estreita não substitui um histórico append-only capaz de explicar os fatos de negócio ocorridos durante o processamento de um pagamento.

A `payment_transaction_entity` também não possui timestamps operacionais. A necessidade de `created_at`, `updated_at` e `status_changed_at`, incluindo a semântica de backfill para dados legados, continua relevante, mas esses campos representam o estado operacional atual e não substituem a auditoria de negócio.

# Status da feature

A implementação da auditoria está pronta para ser retomada.

A notification outbox eliminou a janela entre a confirmação do fato financeiro e a criação da obrigação durável de notificação. A semântica de replay e o fluxo final das notificações também estão documentados.

Este trabalho agora trata somente dos fatos de negócio do SPI. A auditoria de entradas rejeitadas no `kafka-producer` e no SPI foi separada em [`auditoria-rejeicoes-entrada.md`](auditoria-rejeicoes-entrada.md), pois possui origens e garantias diferentes.

# Escopo do MVP

A auditoria inicial registrará somente fatos de negócio.

Eventos previstos:

* `PAYMENT_CREATED`;
* `PAYMENT_STATUS_CHANGED`;
* `SETTLEMENT_APPLIED`;
* replay quando produzir um efeito de negócio relevante.

Ficam fora do MVP:

* auditoria diagnóstica;
* retries técnicos;
* redelivery como evento diagnóstico;
* histórico de DLQ;
* métricas e traces;
* payload PACS original;
* replay que resulte somente em `NOOP`;
* duplicatas homogêneas eliminadas dentro do mesmo batch;
* rejeições de entrada no `kafka-producer` ou no SPI.

# Decisões de modelagem

* A auditoria será append-only no PostgreSQL.
* Cada evento terá uma identidade técnica própria.
* O payload PACS original não será armazenado.
* Serão preservados apenas os dados normalizados necessários para explicar o fato auditado.
* `PAYMENT_STATUS_CHANGED` e `SETTLEMENT_APPLIED` serão eventos separados.
* A criação produzirá somente `PAYMENT_CREATED`, sem um evento adicional de mudança para o status inicial.
* `SETTLEMENT_APPLIED` registrará:
  * `paymentId`;
  * valor;
  * pagador;
  * recebedor;
  * delta debitado;
  * delta creditado.
* Não serão armazenados saldos anteriores e posteriores.
* Não será exigido um ator específico para settlement no MVP.
* Replay `NOOP` não será auditado.
* Quando um replay produzir criação, transição, liquidação ou rejeição, o resultado será representado pelos eventos normais correspondentes.
* A representação técnica do replay, como evento separado ou indicação nos eventos normais, deverá usar a alternativa mais simples após a notification outbox.

# Garantias

Eventos associados a alterações financeiras devem ser persistidos atomicamente com o fato de negócio:

* `PAYMENT_CREATED`;
* `PAYMENT_STATUS_CHANGED`;
* `SETTLEMENT_APPLIED`.

# Retenção e dados

* O MVP utilizará apenas dados sintéticos.
* Não haverá retenção automática inicialmente.
* As tabelas poderão ser limpas manualmente entre testes.
* Particionamento, arquivamento e object storage ficam como evolução futura.
* Credenciais, tokens, chaves privadas e segredos nunca devem ser armazenados.

# Decisões técnicas ainda pendentes

Estas decisões deverão ser resolvidas ao retomar a auditoria, com base no código definitivo após a outbox:

* estrutura física da tabela ou tabelas;
* colunas tipadas e eventual uso de `JSONB`;
* constraints de idempotência por tipo de evento;
* índices mínimos;
* representação final do replay;
* necessidade de identificadores adicionais;
* impacto em throughput, latência, WAL e crescimento da base.

Recomendações técnicas ainda não aprovadas não devem ser tratadas como decisões fechadas.

# Critério para retomada

A feature de auditoria foi retomada depois que:

1. a obrigação de notificar passou a ser persistida duravelmente junto da decisão financeira;
2. a publicação para o Kafka tornou-se recuperável;
3. a semântica dos replays foi documentada;
4. o fluxo final de notificações passou a poder ser incluído na reconstrução auditável.

Ao retomar a feature, preservar claramente a separação entre:

* decisões já fechadas;
* limitações conscientes do MVP;
* dependências da notification outbox;
* decisões técnicas ainda pendentes.
