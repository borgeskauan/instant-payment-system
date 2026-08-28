# Fase 2 — Adotar JDBC como fronteira de persistência

## Objetivo

Remover a stack JPA/Hibernate que não participa dos fluxos reais e tornar os
nomes do código coerentes com a persistência JDBC já usada pelo SPI, sem mudar
SQL, ordem de locks, classificação transacional ou contratos de domínio.

## Mudanças

1. Criar testes de arquitetura simples que exijam os nomes JDBC e a ausência de
   tipos/anotações JPA no runtime.
2. Substituir `Entity` por `PaymentTransactionRow`, uma estrutura interna sem
   annotations de persistência.
3. Renomear `Mapper` para `PaymentTransactionRowMapper`, `JpaAdapter` para
   `JdbcPaymentTransactionRepository` e `FundsJpaAdapter` para
   `JdbcFundsRepository`.
4. Atualizar testes e referências sem alterar o conteúdo das queries JDBC.
5. Remover `spring-boot-starter-data-jpa`, a configuração `spring.jpa` e o
   bootstrap Hibernate.
6. Executar testes focados, busca por resíduos JPA/Hibernate e a suíte completa
   do SPI.

## Gate

* apenas JDBC participa do runtime;
* não restam annotations, dependências ou nomes JPA;
* os testes PostgreSQL preservam idempotência, concorrência, rollback e saldos;
* o diff não altera SQL ou decisões de classificação.
