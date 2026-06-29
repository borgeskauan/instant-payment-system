# Infraestrutura e documentação

Trabalho possível, mas ainda não priorizado. Tarefas ativas ou pausadas já priorizadas ficam em `docs/board/Atividades`.

## Infraestrutura e deploy

**Por que existe**

O projeto nasceu com intenção de rodar os serviços em containers e, futuramente, em Kubernetes. Já existem Dockerfiles e `infra/docker-compose.yml`, então esta frente precisa ser reavaliada antes de virar implementação.

**Tarefas**

- [ ] Conferir quais serviços já estão containerizados.
- [ ] Separar o que é ambiente local com Docker Compose do que seria deploy Kubernetes.
- [ ] Realizar deploy em Kubernetes, se ainda fizer sentido para o projeto.

## Control panel para PSPs

**Por que existe**

A ideia era ter uma interface ou serviço para criar, iniciar, parar e reiniciar PSPs sem depender de inicialização manual. Isso poderia ajudar em cenários com múltiplos PSPs e futura orquestração.

**Tarefas**

- [ ] Decidir se o control panel ainda é necessário.
- [ ] Definir escopo mínimo: criação, start, stop, restart ou apenas visualização.
- [ ] Decidir se isso pertence ao frontend atual, a um serviço separado ou à infraestrutura.

## Documentação geral do projeto

**Por que existe**

O projeto tem documentação espalhada sobre descoberta, fluxo Pix, testes de carga, Kafka e gRPC. Falta uma documentação de entrada que explique como o sistema está organizado e por onde retomar.

**Tarefas**

- [ ] Criar documentação geral do projeto.
- [ ] Mapear os principais serviços: SPI, PSP, DICT, Kafka producer e notification gateway.
- [ ] Explicar como executar o projeto localmente.
- [ ] Explicar o fluxo Pix ponta a ponta em alto nível.
