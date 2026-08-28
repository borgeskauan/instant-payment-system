# Centralizar a configuração runtime dos componentes — concluída

## Problema

Uma mesma propriedade pode ter valores diferentes em `application.yml`, no
fallback de `@Value` e no `docker-compose.yml`. O override do Compose é aplicado
silenciosamente e pode invalidar experimentos: no A/B de
`max.poll.records`, alterar apenas a configuração da aplicação não mudou o valor
efetivo do consumer.

## Objetivo

Definir uma única fonte autoritativa para cada configuração runtime. Compose,
testes e comandos de execução devem consumir ou sobrescrever essa fonte de modo
explícito, sem manter cópias independentes do mesmo valor.

## Escopo

* inventariar propriedades duplicadas entre código, `application.yml`, Compose
  e scripts;
* escolher e documentar a fonte autoritativa;
* remover defaults e overrides redundantes;
* manter overrides de ambiente somente quando forem intencionais e visíveis;
* adicionar uma verificação que exponha a configuração efetiva usada nos
  experimentos de performance.

## Conclusão

* alterar uma propriedade autoritativa muda o valor efetivo do componente;
* divergências entre configuração declarada e efetiva não passam
  silenciosamente;
* os bundles de performance identificam os valores runtime relevantes.

## Resultado

Implementado no commit `38e6a30` para o SPI:

* `application.yml` é a fonte do baseline comportamental homologado;
* properties tipadas substituem defaults embutidos em `@Value`;
* Compose mantém conectividade, recursos e overrides deliberados de deployment;
* o startup registra a configuração efetiva sem credenciais;
* o preparador captura essa linha em `inputs/spi-runtime-config.log`, que o
  runner preserva no bundle do experimento.
