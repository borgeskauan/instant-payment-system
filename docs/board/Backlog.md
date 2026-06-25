# Backlog

Este arquivo guarda trabalho possível, mas ainda não priorizado. Tarefas ativas ou pausadas já priorizadas ficam em `docs/board/Atividades`.

## Backlog

### Validações do DICT

**Por que existe**

O DICT já valida alguns aspectos da chave Pix, como tipo de chave e CPF/CNPJ da própria chave. Ainda falta decidir se ele também deve validar dados da conta e do proprietário ao criar uma chave Pix.

**Tarefas**

- [ ] Validar campos da conta ao criar chave Pix.
- [ ] Validar campos do proprietário ao criar chave Pix.
- [ ] Definir quais validações pertencem ao DICT e quais pertencem ao PSP.

### Consultas auxiliares simuladas

**Por que existe**

O fluxo Pix real depende de consultas e validações auxiliares, como CPF/CNPJ. No projeto, ainda não está claro se isso deve virar um microserviço separado, uma responsabilidade do DICT ou apenas uma validação interna simplificada.

**Tarefas**

- [ ] Decidir onde ficará a consulta/validação de CPF/CNPJ.
- [ ] Implementar consulta simulada de CPF/CNPJ, se ainda fizer sentido para o escopo.

### Separar DICT em um microserviço próprio

**Por que existe**

DICT e SPI são sistemas distintos na infraestrutura real do Banco Central. Hoje ambos rodam na mesma aplicação Spring Boot (`SpiApplication`), compartilhando JVM, banco de dados e porta.

**Tarefas**

- [ ] Extrair `br.kauan.dict.*` para um novo módulo `dict`.
- [ ] Criar `pom.xml`, `application.yml` e Dockerfile próprios para o DICT.
- [ ] Adicionar o DICT como serviço separado no ambiente local.
- [ ] Fazer o SPI chamar o DICT por HTTP em vez de chamada em processo.

### Contrato de preview e execução no PSP

**Por que existe**

Hoje o endpoint de execução de transferência recebe o objeto completo do recebedor retornado pelo preview. Isso reaproveita a consulta ao DICT e mantém o `/transfer/execute` rápido, mas acopla o cliente ao formato interno do recebedor. Uma alternativa melhor é o preview gerar um `previewId`, armazenar temporariamente os dados resolvidos no PSP pagador e fazer o execute receber apenas esse identificador, junto com os dados da transferência.

Os testes de carga atuais em `load-test/` chamam diretamente o fluxo SPI/Kafka por PACS e não dependem dos endpoints `/transfer/preview` e `/transfer/execute` do PSP.

**Tarefas**

- [ ] Fazer `/transfer/preview` retornar um `previewId` além dos dados exibíveis do recebedor.
- [ ] Armazenar temporariamente o resultado do preview no PSP pagador, com expiração.
- [ ] Alterar `/transfer/execute` para receber `previewId` em vez do corpo completo do `receiver`.
- [ ] Validar expiração, reuso e existência do preview antes de executar a transferência.
- [ ] Atualizar Bruno e REST Client para o novo contrato.
- [ ] Manter os testes de carga atuais compatíveis, já que eles exercitam SPI/Kafka diretamente.

### Auditoria completa das transações no SPI sem pesar o hot path

**Por que existe**

Para reduzir pressão no PostgreSQL durante os testes de carga, o SPI passou a persistir no caminho quente apenas os campos necessários para liquidação e roteamento da confirmação: `payment_id`, `amount`, `status`, `sender_bank_code` e `receiver_bank_code`.

Isso ajuda a medir o impacto de uma tabela operacional estreita, mas não resolve a necessidade real de auditoria. Em produção, o sistema precisa conseguir reconstituir a transação completa, incluindo payload recebido, dados das partes e metadados de origem. Essa auditoria não deve voltar a bloquear a liquidação dentro do SLA.

**Tarefas**

- [ ] Definir o modelo de auditoria completo da transação.
- [ ] Decidir se a fonte auditável principal será Kafka, tabela dedicada, outbox ou combinação desses mecanismos.
- [ ] Criar fluxo assíncrono para persistir payload/dados completos sem bloquear o settlement.
- [ ] Registrar metadados de origem: tópico, partição, offset, timestamp de consumo e identificador fim a fim.
- [ ] Manter tabela operacional estreita para o caminho quente de liquidação.
- [ ] Definir política de retenção e consulta para dados de auditoria.
- [ ] Medir o impacto da auditoria assíncrona no load test antes de considerá-la parte do fluxo padrão.

### Normalizar lotes PACS na entrada do Kafka producer

**Por que existe**

O `kafka-producer` já reconhece `pacs.008` e `pacs.002` na borda HTTP e publica nos tópicos internos do SPI um registro Kafka por transação/status. O teste de carga `invariant-kafka-event-one-transaction-15m` validou essa invariante no caminho de load test direto para o Kafka producer.

Ainda falta aplicar a mesma padronização no PSP real. O PSP continua carregando o modelo antigo de lote no domínio e nos serviços (`PaymentBatch`, `StatusBatch`, `BatchDetails`, `handleTransferRequestBatch`, `handleStatusBatch`) e ainda precisa ser simplificado para tratar cada notificação PACS recebida como uma unidade lógica: uma transação ou um status por payload.

**Tarefas**

- [x] Fazer o `kafka-producer` reconhecer `pacs.008` e `pacs.002` na borda HTTP.
- [x] Dividir `pacs.008` com múltiplos `CdtTrfTxInf` em um registro Kafka por transação.
- [x] Dividir `pacs.002` com múltiplos `TxInfAndSts` em um registro Kafka por status.
- [x] Publicar nos tópicos internos do SPI protobufs internos com uma única unidade lógica por registro Kafka.
- [x] Atualizar o SPI para consumir os protobufs internos e manter a PACS isolada na borda.
- [x] Atualizar o load tool para preservar PACS na entrada, mas validar o fluxo interno com um evento Kafka por transação/status.
- [x] Medir impacto em throughput e latência no teste `invariant-kafka-event-one-transaction-15m`.
- [ ] Atualizar o cliente gRPC do PSP para o contrato `NotificationBatch` do notification-gateway e iterar todos os payloads recebidos.
- [ ] Remover o modelo de lote do domínio do PSP (`PaymentBatch`, `StatusBatch`, `BatchDetails`).
- [ ] Trocar APIs internas do PSP para processar unidade lógica: `handleTransferRequest(PaymentTransaction)` e `handleStatus(StatusReport)`.
- [ ] Manter PACS apenas na borda de entrada/saída do PSP, separada dos DTOs internos.
- [ ] Fazer o PSP rejeitar ou sinalizar payload PACS recebido com mais de uma transação/status, se esse caso chegar pela notificação.
- [ ] Atualizar testes do PSP para validar a regra: uma notificação PACS recebida = uma transação/status processado.
- [ ] Definir como ajustar ou preservar metadados do `GrpHdr`, como `MsgId`, `NbOfTxs`, timestamps e identificadores de lote original.
- [ ] Adicionar metadados de rastreabilidade: mensagem original, índice do item, tamanho do lote original, ISPB de origem e hash do payload original.
- [ ] Definir comportamento de falha para publicação parcial: falhar a requisição inteira ou registrar compensação/reprocessamento.

### Observabilidade operacional do fluxo Pix

**Por que existe**

O projeto já tem testes de carga, JFR, traces CSV e métricas de infraestrutura no ambiente local, mas ainda falta uma visão operacional clara para responder rapidamente se o sistema está saudável, se houve degradação e onde está o gargalo. A observabilidade precisa cobrir tanto saúde técnica dos serviços quanto o fluxo de negócio Pix ponta a ponta.

As metas principais são: verificar saúde da aplicação enquanto ela roda, acompanhar throughput e latência do fluxo, detectar degradação de SLA, identificar gargalos entre Kafka, SPI, PostgreSQL e notification-gateway, e comparar o comportamento antes/depois de mudanças.

**Tarefas**

- [ ] Definir métricas de saúde por serviço: container/pod up, restarts, readiness/liveness, CPU, memória, CPU throttling, JVM heap, direct memory e GC.
- [ ] Definir métricas Kafka: consumer lag por tópico/grupo/partição, records consumed/sec, records produced/sec, producer latency, retries, errors e rebalances.
- [ ] Definir métricas do SPI: requests consumidas/sec, statuses recebidos/sec, settlements/sec, notificações enfileiradas/sec, latência de processamento, erros e tempo de queries no PostgreSQL.
- [ ] Definir métricas do notification-gateway: subscribers ativos, notificações/sec, batches/sec, tamanho dos batches, flush por tamanho/tempo, erros gRPC e streams cancelados.
- [ ] Definir métricas de banco: conexões ativas, pool Hikari, query latency, locks, CPU, I/O e slow queries.
- [ ] Definir métricas de negócio/SLA: transações iniciadas, aceitas, confirmadas, não confirmadas, dentro/fora do SLA, p50, p95, p99 e máximo.
- [ ] Criar dashboard de saúde geral do ambiente: serviços, containers/pods, CPU, memória, restarts, throttling, Kafka e PostgreSQL.
- [ ] Criar dashboard do fluxo Pix ponta a ponta: incoming, consumed, settled, outbound status, notifications e confirmations.
- [ ] Criar dashboard de latência por etapa: `http_done -> request_consumed`, `request_consumed -> pacs008_received`, `pacs002_sent -> confirmation_received` e latência end-to-end.
- [ ] Criar dashboard de Kafka: lag, throughput, producer/consumer rate, partitions e rebalances.
- [ ] Criar dashboard de cold start: tempo até readiness, tempo até primeiro consumo, lag inicial, drain rate e tempo até estabilizar p95/p99.
- [ ] Definir alertas mínimos para degradação: container down, restart loop, CPU throttling alto, consumer lag crescendo, p95/p99 acima do alvo, transações não confirmadas e erros Kafka/gRPC/DB.
- [ ] Padronizar labels/dimensões das métricas: serviço, tópico, consumer group, partition, ISPB, tipo de evento e versão/build.
- [ ] Decidir quais métricas vêm de Micrometer/Actuator, Kafka exporter, Postgres exporter, cAdvisor/Node exporter, JFR ou traces próprios.
- [ ] Integrar a observabilidade aos testes de carga para comparar baseline vs mudança: throughput sustentado, p95/p99, lag, CPU por transação e memória por transação.

### Infraestrutura e deploy

**Por que existe**

O projeto nasceu com intenção de rodar os serviços em containers e, futuramente, em Kubernetes. Já existem Dockerfiles e `infra/docker-compose.yml`, então esta frente precisa ser reavaliada antes de virar implementação.

**Tarefas**

- [ ] Conferir quais serviços já estão containerizados.
- [ ] Separar o que é ambiente local com Docker Compose do que seria deploy Kubernetes.
- [ ] Realizar deploy em Kubernetes, se ainda fizer sentido para o projeto.

### Control panel para PSPs

**Por que existe**

A ideia era ter uma interface ou serviço para criar, iniciar, parar e reiniciar PSPs sem depender de inicialização manual. Isso poderia ajudar em cenários com múltiplos PSPs e futura orquestração.

**Tarefas**

- [ ] Decidir se o control panel ainda é necessário.
- [ ] Definir escopo mínimo: criação, start, stop, restart ou apenas visualização.
- [ ] Decidir se isso pertence ao frontend atual, a um serviço separado ou à infraestrutura.

### Documentação geral do projeto

**Por que existe**

O projeto tem documentação espalhada sobre descoberta, fluxo Pix, testes de carga, Kafka e gRPC. Falta uma documentação de entrada que explique como o sistema está organizado e por onde retomar.

**Tarefas**

- [ ] Criar documentação geral do projeto.
- [ ] Mapear os principais serviços: SPI, PSP, DICT, Kafka producer e notification gateway.
- [ ] Explicar como executar o projeto localmente.
- [ ] Explicar o fluxo Pix ponta a ponta em alto nível.

## Histórico

### Definições iniciais

- [x] Definir endpoints dos sistemas: SPI, DICT e PSP.
- [x] Decidir se a estrutura do payload seguiria ISO 20022.
- [x] Definir fluxo de pagamento.
- [x] Definir stack técnica:
  - [x] Java + Spring Boot.
  - [x] Kafka.
  - [x] K6.
  - [x] Kubernetes.

### PACS

- [x] Fazer versão das PACSs para uso interno:
  - [x] pacs.008.
  - [x] pacs.002.
- [x] Converter PACSs para objeto interno e vice-versa:
  - [x] pacs.008.
  - [x] pacs.002.

### DICT

- [x] Implementar API do DICT.
- [x] Realizar implementação básica.
- [x] Barrar request se chave Pix já existe.
- [x] Validar CPF com dígito verificador e não existência prévia.
- [x] Criar enum para validar tipo de chave.

### SPI, PSP e fluxo Pix

- [x] Executar dois PSPs ao mesmo tempo.
- [x] Criar frontend em Angular para simular transações.
- [x] Criar endpoint de login/cadastro no PSP.
- [x] Criar endpoint de cadastro de chave Pix.
- [x] Realizar transação interbancária e intrabancária.
- [x] Melhorar infraestrutura para executar dois PSPs ao mesmo tempo.
- [x] Refatorar código do PSP para desacoplar o modelo usado do modelo do SPI.
- [x] Refatorar localização das classes e pacotes.
- [x] Testar implementação após refatoração.

### Testes de performance

- [x] Realizar teste de performance com K6.
- [x] Realizar vários pedidos de transferência.
- [x] Realizar pedido de transferência diretamente para SPI.
- [x] Realizar consulta de mensagens para um PSP.
- [x] Realizar aceite do pedido.
- [x] Consultar mensagens de confirmação e finalizar o ciclo de teste.
- [x] Implementar long polling de verdade.

### Kafka e notificações

- [x] Implementar tópico Kafka para notificações.
- [x] Criar microserviço de protótipo usando gRPC streaming.
- [x] Realizar comparação com HTTP long polling usando K6.
- [x] Implementar primeiro sem Kafka.
- [x] Desacoplar recebimento e processamento.
- [x] Criar microserviço para receber e postar no Kafka o pedido de transferência.
- [x] Integrar consumo do tópico Kafka no SPI para realizar processamento.
- [x] Criar tópico Kafka para tratar notificações.
- [x] Criar microserviço para conectar no tópico de notificações Kafka e expor endpoint gRPC de server-streaming.
- [x] Adequar teste para se integrar com microserviço gRPC.

### Infraestrutura

- [x] Implementar limite de recursos no teste de load balancer: CPU e RAM.
- [x] Padronizar PostgreSQL nos ambientes:
  - [x] Remover ou isolar a configuração H2 do SPI.
  - [x] Configurar PostgreSQL como banco padrão do SPI.
  - [x] Habilitar e revisar migrations com Flyway.
  - [x] Garantir que o ambiente local suba com PostgreSQL sem passos manuais extras.
