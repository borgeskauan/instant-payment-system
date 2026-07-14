# Segurança

Trabalho possível, mas ainda não priorizado. Tarefas ativas ou pausadas já priorizadas ficam em `docs/board/Atividades`.

## Autenticação PSP -> kafka-producer e identidade no SPI

**Por que existe**

Alguns fluxos do PSP não passam pelo `notification-gateway`. Eles chegam primeiro no `kafka-producer` por requisições HTTP e depois seguem para o SPI via Kafka. Nesse modelo, o SPI não autentica diretamente o PSP na conexão original; quem está na borda de confiança é o `kafka-producer`.

Por isso, o `kafka-producer` precisa autenticar o PSP, extrair o ISPB autenticado e propagar essa identidade de forma confiável para o SPI, por exemplo via header Kafka. O SPI deve confiar nessa identidade apenas porque confia no `kafka-producer` e porque a escrita nos tópicos internos é controlada. O ISPB informado no payload continua sendo validado, mas não deve ser tratado como identidade autenticada.

**Tarefas**

- [ ] Proteger as requisições HTTP PSP -> `kafka-producer` com autenticação forte, preferencialmente usando o mesmo modelo de mTLS.
- [ ] Fazer o `kafka-producer` extrair o ISPB da identidade autenticada do PSP.
- [ ] Impedir que o `kafka-producer` confie apenas no ISPB informado no payload.
- [ ] Publicar a identidade autenticada no Kafka de forma explícita, por exemplo em header `authenticated-ispb`.
- [ ] Garantir que apenas produtores internos autorizados consigam escrever nos tópicos consumidos pelo SPI.
- [ ] Fazer o SPI validar se o payload recebido é compatível com o ISPB autenticado propagado pelo `kafka-producer`.
- [ ] Rejeitar ou classificar como anomalia mensagens cujo payload tente agir em nome de outro ISPB.
- [ ] Adicionar testes negativos para PSP tentando agir como outro ISPB via HTTP no `kafka-producer`.
- [ ] Documentar o contrato de confiança: certificado válido no `kafka-producer` -> PSP autenticado -> header de ISPB autenticado -> SPI autoriza a operação.
