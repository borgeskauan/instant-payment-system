# Auditoria de rejeições de entrada

- [ ] Auditar entradas rejeitadas no `kafka-producer` e no SPI

# Contexto

Entradas podem ser recusadas antes de produzir um fato financeiro. Essas recusas não pertencem ao histórico de negócio append-only das transações do SPI: elas surgem em serviços e etapas diferentes, não alteram o pagamento e possuem garantias distintas.

Esta task foi separada de [`auditoria-transacoes-spi.md`](../../concluidas/auditoria-transacoes-spi.md) para que a auditoria financeira possa permanecer atômica e simples, sem acoplar seu desenho ao tratamento de rejeições nas bordas.

# Objetivo

Registrar causas de rejeição suficientes para explicar por que uma entrada não avançou, sem armazenar o payload PACS original e sem transformar a auditoria em histórico de retries técnicos ou de DLQ.

# Escopo do MVP

Mapear e auditar rejeições terminais de entrada em dois pontos:

## `kafka-producer`

* falha de autenticação do PSP;
* falha de autorização do PSP;
* payload PACS inválido ou incompatível com o contrato.

## SPI

* ausência de identidade autenticada;
* payload interno inválido;
* PSP não autorizado para a mensagem;
* duplicata divergente de pagamento;
* status report divergente ou referente a pagamento desconhecido.

O status de negócio `REJECTED` de um pagamento não faz parte desta task. Ele é uma transição válida e pertence à auditoria de transações do SPI.

# Fora do MVP

* retries técnicos e redeliveries;
* indisponibilidade ou falha recuperável de publicação no Kafka;
* histórico de mensagens e tentativas da DLQ;
* métricas, traces e stack traces;
* payload PACS original;
* credenciais, certificados, tokens, chaves ou outros segredos;
* rejeições internas que não representam uma entrada terminalmente recusada.

# Garantias

As rejeições na borda HTTP do `kafka-producer` serão auditadas em regime best effort:

* falha da auditoria não altera a resposta HTTP da rejeição;
* falha da auditoria permanece visível em logs e, quando houver observabilidade adequada, em métricas ou alertas;
* `paymentId` pode ser nulo quando não puder ser extraído com confiança.

A garantia apropriada para rejeições detectadas no SPI ainda precisa ser definida considerando o ACK Kafka e a publicação na DLQ. A auditoria não deve introduzir confirmação prematura, loop de retry indevido nem perda silenciosa da rejeição.

# Dados mínimos

O desenho deve avaliar, no mínimo:

* identidade técnica do evento;
* instante da rejeição usando o relógio do banco quando houver persistência PostgreSQL;
* serviço e etapa de origem;
* categoria e código estável da causa;
* `paymentId`, quando confiável;
* ISPB autenticado, quando disponível;
* tipo da mensagem, quando identificável;
* identificadores de correlação necessários para suporte e deduplicação.

Detalhes livres de exceção devem ser limitados e sanitizados. O evento deve preferir códigos estáveis a mensagens dependentes da implementação.

# Decisões técnicas pendentes

* banco, tabela ou tabelas responsáveis pelos eventos produzidos por serviços distintos;
* necessidade de uma porta comum de auditoria ou implementações independentes;
* taxonomia final das causas;
* estratégia de idempotência para reenvio da mesma entrada;
* garantia das rejeições pré-SQL e pós-SQL no SPI;
* ordem entre persistência da auditoria, publicação na DLQ e ACK do input;
* comportamento quando auditoria ou DLQ estiver indisponível;
* identificadores disponíveis antes da desserialização completa;
* índices, retenção, acesso operacional e impacto de volume.

Recomendações ainda não aprovadas não devem ser tratadas como decisões fechadas.

# Critérios de aceite

* todas as categorias terminais em escopo possuem causa estável e testada;
* uma rejeição pode ser correlacionada à origem sem depender do payload PACS original;
* falha best effort no `kafka-producer` não muda o status HTTP que seria retornado;
* a integração no SPI preserva a semântica correta de DLQ, ACK e retry;
* replay ou redelivery não cria eventos indevidos segundo a estratégia de idempotência definida;
* nenhum segredo ou payload PACS original é persistido;
* testes cobrem sucesso, falha da auditoria e ausência de `paymentId` confiável;
* documentação diferencia claramente rejeição de entrada, rejeição de negócio e falha técnica recuperável.
