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
