# Autenticação PSP -> kafka-producer e identidade no SPI

- [ ] Autenticação PSP -> `kafka-producer` e identidade no SPI

**Por que existe**

Alguns fluxos do PSP não passam pelo `notification-gateway`. Eles chegam primeiro no `kafka-producer` por requisições HTTP e depois seguem para o SPI via Kafka. Nesse modelo, o PSP não se autentica no Kafka e o SPI não autentica diretamente o PSP na conexão original. Quem está na borda de confiança é o `kafka-producer`.

Por isso, o `kafka-producer` precisa autenticar o PSP na borda HTTP, extrair o ISPB autenticado e validar o que for possível contra essa identidade antes de publicar a mensagem interna. Depois disso, ele propaga o ISPB autenticado para o SPI em um header Kafka. O SPI deve tratar esse header como contexto confiável apenas porque ele foi criado pelo `kafka-producer`, não porque veio do PSP. O ISPB informado no payload continua sendo validado, mas não deve ser tratado como identidade autenticada.

As regras de autorização são:

- `pacs.008`: somente o PSP pagador pode enviar a solicitação. O ISPB autenticado deve ser igual ao `DbtrAgt` de todas as transações da requisição e, em replay de pagamento existente, ao `sender_bank_code` persistido.
- `pacs.002`: somente o PSP recebedor da transação original pode enviar `ACSP` ou `RJCT`. Como o payload não contém informação suficiente para provar esse vínculo, o SPI deve comparar o ISPB autenticado com o `receiver_bank_code` persistido para o pagamento.

**Tarefas**

- [x] Configurar mTLS obrigatório entre PSP e `kafka-producer`.
  - [x] Usar o mesmo contrato de identidade do `notification-gateway`: certificado com `SAN URI = urn:pix:ispb:<ISPB>`.
  - [x] Alterar `generate-local-mtls-certs.sh init` para gerar o certificado de servidor do `kafka-producer`, assinado pela mesma CA local e com SAN DNS para `kafka-producer` e `localhost`.
  - [x] Montar o certificado, a chave privada e a CA no container do `kafka-producer`, mantendo a chave legível apenas pelo usuário do processo.
  - [x] Remover fallback automático para plaintext; falha de TLS ou certificado deve falhar a requisição ou a inicialização.
  - [x] Extrair no `kafka-producer` o ISPB autenticado pelo certificado da conexão HTTP.
- [x] Ajustar o contrato HTTP do `kafka-producer`.
  - [x] Remover o ISPB das rotas `/{ispb}/transfer` e `/{ispb}/transfer/status`.
  - [x] Atualizar PSP, load-tool e demais clientes para usar `/transfer` e `/transfer/status`.
  - [x] Impedir que o `kafka-producer` confie apenas no ISPB informado no payload.
- [x] Autorizar `pacs.008` na borda HTTP.
  - [x] Validar todas as transações antes de publicar qualquer record.
  - [x] Exigir que todos os `DbtrAgt` sejam iguais ao ISPB autenticado.
  - [x] Rejeitar com HTTP `403` toda a requisição quando qualquer transação tentar agir em nome de outro ISPB, sem publicação parcial no Kafka.
- [x] Propagar a identidade autenticada para o SPI.
  - [x] Publicar o ISPB autenticado no header Kafka `authenticated-ispb`.
  - [x] Criar o header exclusivamente a partir do certificado, nunca a partir de informação recebida do cliente HTTP.
- [ ] Autorizar mensagens no SPI antes de qualquer efeito.
  - [ ] Para `pacs.008`, comparar `authenticated-ispb` com o ISPB pagador do payload e, quando o pagamento já existir, com o `sender_bank_code` persistido.
  - [ ] Para `pacs.002`, comparar `authenticated-ispb` com o `receiver_bank_code` persistido antes de update, idempotência ou settlement.
  - [ ] Impedir que mensagem sem `authenticated-ispb`, com header duplicado ou com identidade incompatível altere estado, saldo ou produza efeitos laterais.
  - [ ] Publicar erros determinísticos de segurança na DLQ: `NOT_AUTHENTICATED` para header ausente, duplicado ou malformado; `UNAUTHORIZED_PSP` para identidade válida sem autorização sobre a mensagem ou transação.
- [ ] Cobrir autenticação e autorização com testes.
  - [x] Testar PSP sem certificado e certificado inválido.
  - [x] Testar `pacs.008` com pagador divergente na borda HTTP.
  - [ ] Testar replay de `paymentId` pertencente a outro pagador no SPI.
  - [x] Testar que um `pacs.008` com múltiplas transações, sendo uma delas não autorizada, é rejeitado integralmente e não publica nenhum record Kafka.
  - [ ] Testar que um `pacs.002` enviado por PSP diferente do recebedor não altera a transação e é publicado na DLQ.
- [ ] Documentar o contrato de confiança: certificado válido na borda HTTP do `kafka-producer` -> PSP autenticado -> header Kafka interno `authenticated-ispb` -> SPI autoriza a operação.

**Notas**

- O PSP não se autentica no Kafka e não publica diretamente nos tópicos internos.
- A proteção de escrita nos tópicos Kafka é uma preocupação de infraestrutura interna, separada da autenticação PSP. Ela continua importante, mas não é o mecanismo que autentica o PSP neste fluxo.
- Certificado ausente, inválido ou configuração TLS incompleta deve falhar de forma explícita. Não deve existir downgrade automático para HTTP/plaintext.
- O certificado cliente já emitido para cada PSP deve ser reutilizado tanto no `notification-gateway` quanto no `kafka-producer`; cada servidor possui seu próprio certificado, ambos assinados pela mesma CA local.
- `NOT_AUTHENTICATED` representa ausência de identidade autenticada válida e `UNAUTHORIZED_PSP` representa identidade válida sem permissão, seguindo a separação conceitual entre autenticação e autorização.
- A rejeição integral e síncrona por autorização é possível no `pacs.008`, pois o `DbtrAgt` está presente no payload.
- No `pacs.002`, a autorização depende do estado persistido no SPI. A requisição HTTP já terá sido aceita pelo `kafka-producer`; cada status não autorizado deve ser bloqueado e enviado à DLQ pelo SPI.
- Separar PACS inválido como HTTP `400` e refinar respostas para falha de publicação Kafka não fazem parte desta task de segurança.
