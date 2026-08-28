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

Implementado inicialmente no commit `38e6a30` para o SPI e concluído durante o cleanup dos demais componentes:

* `application.yml` é a fonte do baseline comportamental homologado;
* properties tipadas substituem defaults embutidos em `@Value`;
* Compose mantém conectividade, recursos e overrides deliberados de deployment;
* o startup registra a configuração efetiva sem credenciais;
* o preparador captura essa linha em `inputs/spi-runtime-config.log`, que o
  runner preserva no bundle do experimento.

No Notification Gateway, `application.yml` passou a ser o único baseline, `NotificationGatewayProperties` concentra e valida as propriedades próprias do componente e `KafkaProperties` fornece a configuração Kafka padrão do Spring. Os fallbacks repetidos em `@Value` e os placeholders redundantes de variáveis de ambiente foram removidos; overrides continuam disponíveis pela convenção canônica do Spring, enquanto o alias compartilhado `KAFKA_BOOTSTRAP_SERVERS` permanece explícito por ser uma decisão de deployment comum à stack.

No Kafka Producer, `AppConfig` permanece como fonte tipada única porque o componente não usa Spring. Conectividade Kafka e caminhos dos certificados continuam sendo configuração de deployment, os parâmetros homologados do producer continuam no código e o override não utilizado de `SERVER_PORT` foi removido.
