# Fases 5–7 — Configuração, acabamento e validação final do SPI

## Objetivo

Deixar o comportamento homologado do SPI em uma única configuração de fábrica,
remover resíduos estruturais comprovados e validar o estado final funcional e
de performance.

## Fase 5 — configuração runtime

1. Inventariar defaults e overrides em Java, `application.yml`, Compose e
   scripts.
2. Introduzir propriedades tipadas com `@ConfigurationProperties` para Kafka e
   para a pipeline de notificações.
3. Manter no `application.yml` o baseline comportamental homologado, incluindo
   concorrência `1` nos dois listeners.
4. Remover fallbacks em `@Value` e overrides comportamentais duplicados do
   Compose. Conectividade, credenciais e recursos continuam pertencendo ao
   deployment.
5. Preservar a capacidade nativa do Spring de override por ambiente, sem
   placeholders `${VAR:default}` duplicando defaults no YAML.
6. Fazer o bundle de performance expor os valores efetivos relevantes usados
   pelo SPI.

## Fase 6 — acabamento estrutural

1. Procurar DTOs, mappers, APIs, properties, annotations e dependências sem
   consumidores.
2. Remover somente resíduos comprovados; não criar novas camadas.
3. Alinhar nomes/packages que ainda descrevam arquiteturas removidas.
4. Atualizar documentação ativa e manter documentos históricos como evidência.
5. Reduzir fixtures duplicadas somente quando isso não reduzir cobertura
   semântica.

## Fase 7 — validação

1. Executar a suíte completa do SPI com PostgreSQL/Flyway.
2. Executar testes funcionais do load-test.
3. Recriar a stack e executar `mixed-outcomes-smoke`.
4. Executar uma única regressão `mixed-outcomes-2k-15m`, pois a stack de
   persistência e o schema foram alterados.
5. Comparar outcomes, rolling mínimo, latências e recursos com o baseline
   qualificado vigente.
6. Atualizar o roadmap e a documentação arquitetural com o resultado final.

## Gate

* um default por propriedade, pertencente ao componente;
* overrides de deployment explícitos e configuração efetiva observável;
* nenhuma referência runtime a arquiteturas removidas;
* comportamento funcional preservado e benchmark sem regressão material.
