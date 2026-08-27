# Infraestrutura e deploy

**Por que existe**

O projeto nasceu com intenção de rodar os serviços em containers e, futuramente, em Kubernetes. Já existem Dockerfiles e `infra/docker-compose.yml`, então esta frente precisa ser reavaliada antes de virar implementação.

**Tarefas**

- [ ] Conferir quais serviços já estão containerizados.
- [ ] Separar o que é ambiente local com Docker Compose do que seria deploy Kubernetes.
- [ ] Se Kubernetes voltar a ser priorizado, usar as evidências dos testes de
      performance para definir e justificar `requests` e `limits` de CPU e
      memória por serviço.
- [ ] Realizar deploy em Kubernetes, se ainda fizer sentido para o projeto.
