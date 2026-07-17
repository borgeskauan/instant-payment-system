# Autenticação PSP -> kafka-producer e identidade no SPI

- [ ] Autenticação PSP -> `kafka-producer` e identidade no SPI

**Por que existe**

Alguns fluxos do PSP não passam pelo `notification-gateway`. Eles chegam primeiro no `kafka-producer` por requisições HTTP e depois seguem para o SPI via Kafka. Nesse modelo, o PSP não se autentica no Kafka e o SPI não autentica diretamente o PSP na conexão original. Quem está na borda de confiança é o `kafka-producer`.

Por isso, o `kafka-producer` precisa autenticar o PSP na borda HTTP, extrair o ISPB autenticado e validar o payload contra essa identidade antes de publicar a mensagem interna. Depois disso, ele propaga o ISPB autenticado para o SPI como contexto interno, por exemplo em um header Kafka. O SPI deve tratar esse header como contexto confiável apenas porque ele foi criado pelo `kafka-producer`, não porque veio do PSP. O ISPB informado no payload continua sendo validado, mas não deve ser tratado como identidade autenticada.

**Tarefas**

- [ ] Proteger as requisições HTTP PSP -> `kafka-producer` com autenticação forte, preferencialmente usando o mesmo modelo de mTLS.
- [ ] Remover fallback automático para plaintext no canal PSP -> `kafka-producer`; falha de TLS/certificado deve falhar a requisição ou a inicialização, não degradar para HTTP simples.
- [ ] Fazer o `kafka-producer` extrair o ISPB da identidade autenticada do PSP na conexão HTTP.
- [ ] Usar o mesmo contrato de identidade da conexão PSP -> `notification-gateway`: certificado com `SAN URI = urn:pix:ispb:<ISPB>`.
- [ ] Impedir que o `kafka-producer` confie apenas no ISPB informado no payload HTTP.
- [ ] Fazer o `kafka-producer` rejeitar payloads incompatíveis com o ISPB autenticado antes de publicar no Kafka.
- [ ] Publicar a identidade autenticada no Kafka como contexto interno, em header `authenticated-ispb`.
- [ ] Garantir que o header `authenticated-ispb` seja criado/sobrescrito pelo `kafka-producer`, nunca aceito do cliente HTTP.
- [ ] Fazer o SPI validar se o payload recebido é compatível com o ISPB autenticado propagado pelo `kafka-producer`.
- [ ] Rejeitar ou classificar como anomalia mensagens internas sem `authenticated-ispb` ou com payload incompatível.
- [ ] Adicionar testes negativos para PSP sem certificado, certificado inválido e PSP tentando agir como outro ISPB via HTTP no `kafka-producer`.
- [ ] Documentar o contrato de confiança: certificado válido na borda HTTP do `kafka-producer` -> PSP autenticado -> header Kafka interno `authenticated-ispb` -> SPI autoriza a operação.

**Notas**

- O PSP não se autentica no Kafka e não publica diretamente nos tópicos internos.
- A proteção de escrita nos tópicos Kafka é uma preocupação de infraestrutura interna, separada da autenticação PSP. Ela continua importante, mas não é o mecanismo que autentica o PSP neste fluxo.
- Certificado ausente, inválido ou configuração TLS incompleta deve falhar de forma explícita. Não deve existir downgrade automático para HTTP/plaintext.



----

perguntas:
- devemos de fato usar http aqui? sera que outro modelo de transporte nao facilita a autenticacao?
- pq mTLS e nao outro modelo de autenticacao? token jwt por exemplo. qual o usado no SPI real?
- o kafka-producer hoje eh passthrough para o payload ou ele ja esta fazendo alguma validacao do payload? isso afeta performance.