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

### Padronizar PostgreSQL nos ambientes

**Por que existe**

O SPI ainda tem configuração de H2 em `application.yml`, apesar de o projeto já ter PostgreSQL no `docker-compose.yml`. Usar PostgreSQL de forma consistente reduz diferença entre ambiente local, testes e execução real.

**Tarefas**

- [ ] Remover ou isolar a configuração H2 do SPI.
- [ ] Configurar PostgreSQL como banco padrão do SPI.
- [ ] Habilitar e revisar migrations com Flyway.
- [ ] Garantir que o ambiente local suba com PostgreSQL sem passos manuais extras.

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
