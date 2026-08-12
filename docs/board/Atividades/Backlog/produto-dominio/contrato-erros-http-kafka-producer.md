# Contrato de erros HTTP do kafka-producer

**Por que existe**

O `kafka-producer` ainda responde `500` para categorias diferentes de falha. Depois da implementação da autenticação PSP, o tratamento específico de segurança ficará separado, mas ainda será necessário distinguir payload PACS inválido de indisponibilidade ou falha de publicação no Kafka.

**Tarefas**

- [ ] Responder HTTP `400` para PACS inválido ou incompatível com o contrato de entrada.
- [ ] Manter falhas de publicação Kafka como erro recuperável `5xx`, permitindo retry seguro pelo cliente.
- [ ] Definir o comportamento quando apenas parte dos records de uma requisição for confirmada pelo Kafka.
- [ ] Adicionar testes de contrato para payload inválido, broker indisponível e publicação parcial.
