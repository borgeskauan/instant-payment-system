# Centralizar autorização de PACS.008 no SPI

- [ ] Remover do `kafka-producer` a decisão de autorização baseada no pagador e tornar o SPI seu único dono

## Contexto

O `kafka-producer` autentica o PSP pelo certificado mTLS, extrai o ISPB da SAN e
transporta essa identidade no Kafka. Atualmente ele também compara o ISPB
autenticado com o pagador declarado no PACS.008. O SPI repete a mesma comparação
ao consumir a mensagem e a persistência PostgreSQL ainda possui outra defesa
equivalente.

A autenticação do canal pertence ao ingresso. A decisão de que somente o PSP
pagador pode iniciar o pagamento é uma regra de negócio e deve ter uma
implementação autoritativa no SPI.

## Direção

- Manter no `kafka-producer` a autenticação mTLS e a extração do ISPB do
  certificado.
- Manter a identidade autenticada como contexto confiável da mensagem, em
  header e chave Kafka definidos pelo ingresso.
- Remover do `kafka-producer` a comparação entre essa identidade e o pagador do
  PACS.008.
- Fazer o SPI comparar o ISPB autenticado com o pagador declarado e ser o único
  responsável pela classificação da entrada não autorizada.
- Manter no PostgreSQL somente a verificação que depende de estado
  compartilhado: quando um `payment_id` conflitante já pertence a outro PSP, a
  transação perdedora precisa consultar o pagamento persistido.
- Garantir por ACL e isolamento de rede que produtores não autorizados não
  possam forjar o header nos tópicos internos.

O `kafka-producer` continua convertendo PACS.008 para a mensagem interna. O que
sai dele é a decisão de autorização, não o campo de pagador que compõe o
pagamento.

## Decisão de contrato pendente

Antes da implementação, definir o resultado externamente observável da entrada
não autorizada. Hoje o ingresso pode responder erro HTTP antes da publicação.
Com a decisão apenas no SPI, o HTTP tende a confirmar o recebimento técnico com
`2xx`, enquanto a rejeição ocorre assincronamente.

Definir explicitamente:

- se a entrada vai somente para DLQ/auditoria de rejeição;
- se o PSP deve receber alguma resposta assíncrona;
- como a mudança afeta o contrato de erros HTTP e os testes existentes.

## Testes e critérios de aceite

- o ISPB usado pelo SPI deriva da identidade mTLS transportada pelo ingresso,
  nunca de um campo autodeclarado pelo payload;
- PACS.008 cujo pagador coincide com o PSP autenticado continua sendo
  processado normalmente;
- PACS.008 cujo pagador diverge do PSP autenticado não cria pagamento, reserva,
  auditoria financeira ou outbox de negócio;
- conflito com `payment_id` pertencente a outro PSP continua sendo detectado
  com base no estado persistido;
- o comportamento HTTP, DLQ e eventual resposta assíncrona corresponde ao
  contrato escolhido;
- a regra `authenticatedIspb == senderIspb` deixa de possuir implementações
  concorrentes em mais de um componente;
- testes de segurança cobrem header ausente, duplicado, malformado e tentativa
  de publicação interna não autorizada.

## Relações

- [`Contrato de erros HTTP do kafka-producer`](contrato-erros-http-kafka-producer.md)
- [`Auditoria de rejeições de entrada`](../operacao-testes/auditoria-rejeicoes-entrada.md)

## Fora de escopo

- alterar autenticação mTLS ou o formato da identidade do PSP;
- remover o contexto autenticado da mensagem Kafka;
- permitir publicação direta de PSPs nos tópicos internos;
- otimizar a persistência PACS.008 ou misturar esta mudança com um A/B de
  performance;
- alterar a autorização de PACS.002 sem um desenho específico.
