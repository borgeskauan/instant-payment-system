# Segurança

Trabalho possível, mas ainda não priorizado. Tarefas ativas ou pausadas já priorizadas ficam em `docs/board/Atividades`.

## Identidade e autorização do PSP

**Por que existe**

Hoje os fluxos entre PSP, `notification-gateway` e SPI ainda dependem demais de identificadores informados pela própria mensagem ou pelo cliente. Para evoluir o sistema com entrega confiável, ACK e autorização por participante, a identidade do PSP precisa vir de um mecanismo autenticado e verificável.

O modelo preferido é usar mTLS: o PSP apresenta um certificado de cliente, o serviço valida esse certificado com uma CA confiável e a aplicação associa a identidade autenticada ao ISPB autorizado. A partir disso, o ISPB informado em payloads ou mensagens deixa de ser fonte de confiança e passa a ser apenas dado de negócio a ser validado contra a identidade autenticada.

**Tarefas**

- [ ] Proteger a conexão gRPC entre PSP e `notification-gateway` com mTLS.
- [ ] Fazer o PSP apresentar certificado de cliente ao abrir o stream gRPC.
- [ ] Fazer o `notification-gateway` validar o certificado do PSP usando a CA confiável.
- [ ] Definir como o certificado identifica o PSP e como essa identidade é vinculada ao ISPB.
- [ ] Associar automaticamente cada stream gRPC ao ISPB autenticado.
- [ ] Remover a necessidade de `Subscribe { ispb }` como mecanismo de identidade, mantendo `Subscribe` apenas se futuramente servir para negociação de capacidades ou metadados.
- [ ] Garantir que o `notification-gateway` envie para cada stream apenas deliveries destinadas ao ISPB autenticado.
- [ ] Garantir que o PSP só consiga dar ACK em deliveries destinadas ao próprio ISPB autenticado.
- [ ] Adicionar testes negativos para PSP sem certificado, certificado inválido e ACK de delivery de outro ISPB.

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
