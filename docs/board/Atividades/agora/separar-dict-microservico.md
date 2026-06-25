# Separar DICT em um microserviço próprio

- [ ] Separar DICT em um microserviço próprio

**Por que existe**

DICT e SPI são sistemas distintos na infraestrutura real do Banco Central. Hoje ambos rodam na mesma aplicação Spring Boot (`SpiApplication`), compartilhando JVM, banco de dados e porta.

**Tarefas**

- [ ] Extrair `br.kauan.dict.*` para um novo módulo `dict`.
- [ ] Criar `pom.xml`, `application.yml` e Dockerfile próprios para o DICT.
- [ ] Adicionar o DICT como serviço separado no ambiente local.
- [ ] Fazer o SPI chamar o DICT por HTTP em vez de chamada em processo.

**Referências**

- `spi/src/main/java/br/kauan/dict/`: implementação atual do DICT dentro do SPI.
- `spi/src/main/java/br/kauan/SpiApplication.java`: aplicação Spring Boot que hoje inicializa SPI e DICT juntos.
- `infra/docker-compose.yml`: ambiente local onde o novo serviço DICT deve ser adicionado.
