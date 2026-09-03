# Contrato de erros HTTP do kafka-producer

- [x] Distinguir entrada PACS inválida de falha recuperável de publicação

## Estado

Concluída em 28/08/2026 durante o cleanup do `kafka-producer`.

## Contrato entregue

| resultado | resposta HTTP |
| --- | --- |
| certificado sem identidade PSP válida | `401` |
| PACS inválido ou não representável no contrato interno | `400` |
| todos os records confirmados pelo Kafka | `200` |
| falha de publicação Kafka ou defeito interno | `500` |

Quando um envelope contém vários records, o `200` só é produzido depois que todos são confirmados. Uma falha parcial retorna `500`; o PSP pode repetir o envelope inteiro e a idempotência do SPI absorve records que já tenham sido publicados. Não existe rollback distribuído de records que o Kafka já confirmou.

## Evidências

* `InvalidPacsPayloadException` diferencia entrada inválida de falhas internas;
* `ReactorNettyPaymentServerTest` protege `200`, `400`, `401` e `500`;
* `KafkaPaymentPublisherTest` protege conversão, tópicos, chave/header autenticado, confirmação de todos os records e falha parcial;
* a suíte final do Producer passou com 36 testes;
* o smoke integrado concluiu todas as submissões e outcomes sem violação.

Implementação principal: `da77533 refactor: simplify authenticated payment ingress`.

## Fora do contrato

O status HTTP não representa admissão, autorização ou resultado financeiro do pagamento. Essas decisões pertencem ao SPI e aos outcomes assíncronos.
