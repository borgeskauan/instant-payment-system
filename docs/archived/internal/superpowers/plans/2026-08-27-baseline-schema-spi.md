# Fase 3 — Criar o baseline atual do schema SPI

## Objetivo

Substituir as migrations experimentais V1–V18 por um baseline que cria
diretamente o schema homologado, assumindo banco novo como primeira versão
suportada, e preservar a evolução arquitetural em documentação e no Git.

## Mudanças

1. Caracterizar por testes o schema final: tipos, tabelas, colunas, constraints,
   índices e parâmetros físicos usados pelo runtime.
2. Registrar em um documento de portfólio a evolução de buckets para saldo por
   participante, da outbox com lifecycle para notificação imutável, da auditoria
   técnica para fatos de negócio e da separação de estados/reasons.
3. Criar `V1__Create_spi_baseline.sql` com o estado final equivalente a V1–V18.
4. Remover os SQLs históricos do diretório executável de Flyway. O histórico
   permanece acessível pelo Git e pelas referências documentais.
5. Remover testes cujo único propósito era executar upgrades intermediários e
   manter/fortalecer testes do schema final.
6. Validar criação limpa em PostgreSQL real e executar a suíte completa.

## Compatibilidade

* somente bancos novos são suportados pelo baseline;
* não há upgrade executável de instalações baseadas em V1–V18;
* a política deve estar explícita no guia ativo da aplicação.
