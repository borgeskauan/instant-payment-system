# Centralizar autorização de PACS.008 no SPI

- [x] Remover do `kafka-producer` a decisão de autorização baseada no pagador e tornar o SPI seu único dono

## Estado

Concluída em 28/08/2026 durante o cleanup dos projetos.

## Resultado

* o `kafka-producer` autentica o PSP por mTLS, extrai o ISPB do certificado e o transporta como chave e header Kafka;
* o Producer não compara mais a identidade autenticada com o pagador declarado no PACS.008;
* o SPI valida o header autenticado e concentra a regra `authenticatedIspb == senderIspb` em `PaymentAdmissionPolicy`;
* uma entrada não autorizada não cria pagamento, reserva, auditoria financeira ou obrigação de notificação e é encaminhada para a DLQ com categoria própria;
* o HTTP confirma com `2xx` somente o recebimento técnico e a publicação no Kafka; a autorização de negócio ocorre assincronamente no SPI;
* a defesa baseada no pagamento persistido permanece para conflitos de identidade concorrentes ou preexistentes.

O Producer continua responsável pela autenticação da fronteira e pela conversão representável do PACS. O SPI é o único dono da autorização de negócio. Auditoria operacional dessas rejeições permanece separada em [`Auditoria de rejeições de entrada`](../Backlog/produto-dominio/auditoria-rejeicoes-entrada.md).

## Evidências

* `PspAuthorizationException` e o contrato HTTP `403` foram removidos do Producer;
* `PaymentAdmissionPolicyTest`, `PaymentMessageConsumerTest` e `KafkaDlqConfigTest` cobrem autorização, ausência ou invalidade do header e DLQ;
* as suítes finais passaram com 36 testes no Producer e 197 testes no SPI;
* o smoke integrado misto concluiu sem outcomes ausentes, contraditórios ou violações.

Implementação principal: `da77533 refactor: simplify authenticated payment ingress`.

## Limitação vigente

A confiança no header interno depende de ACL e isolamento de rede impedindo produtores externos de publicar diretamente nos tópicos do SPI. A homologação de segurança de uma implantação distribuída não fez parte desta task.
