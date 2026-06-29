# Histórico

## Definições iniciais

- [x] Definir endpoints dos sistemas: SPI, DICT e PSP.
- [x] Decidir se a estrutura do payload seguiria ISO 20022.
- [x] Definir fluxo de pagamento.
- [x] Definir stack técnica:
  - [x] Java + Spring Boot.
  - [x] Kafka.
  - [x] K6.
  - [x] Kubernetes.

## PACS

- [x] Fazer versão das PACSs para uso interno:
  - [x] pacs.008.
  - [x] pacs.002.
- [x] Converter PACSs para objeto interno e vice-versa:
  - [x] pacs.008.
  - [x] pacs.002.

## DICT

- [x] Implementar API do DICT.
- [x] Realizar implementação básica.
- [x] Barrar request se chave Pix já existe.
- [x] Validar CPF com dígito verificador e não existência prévia.
- [x] Criar enum para validar tipo de chave.

## SPI, PSP e fluxo Pix

- [x] Executar dois PSPs ao mesmo tempo.
- [x] Criar frontend em Angular para simular transações.
- [x] Criar endpoint de login/cadastro no PSP.
- [x] Criar endpoint de cadastro de chave Pix.
- [x] Realizar transação interbancária e intrabancária.
- [x] Melhorar infraestrutura para executar dois PSPs ao mesmo tempo.
- [x] Refatorar código do PSP para desacoplar o modelo usado do modelo do SPI.
- [x] Refatorar localização das classes e pacotes.
- [x] Testar implementação após refatoração.

## Testes de performance

- [x] Realizar teste de performance com K6.
- [x] Realizar vários pedidos de transferência.
- [x] Realizar pedido de transferência diretamente para SPI.
- [x] Realizar consulta de mensagens para um PSP.
- [x] Realizar aceite do pedido.
- [x] Consultar mensagens de confirmação e finalizar o ciclo de teste.
- [x] Implementar long polling de verdade.

## Kafka e notificações

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

## Infraestrutura

- [x] Implementar limite de recursos no teste de load balancer: CPU e RAM.
- [x] Padronizar PostgreSQL nos ambientes:
  - [x] Remover ou isolar a configuração H2 do SPI.
  - [x] Configurar PostgreSQL como banco padrão do SPI.
  - [x] Habilitar e revisar migrations com Flyway.
  - [x] Garantir que o ambiente local suba com PostgreSQL sem passos manuais extras.
