# Autenticação mTLS do PSP no notification-gateway

- [ ] Autenticação mTLS do PSP no `notification-gateway`

**Por que existe**

O stream gRPC entre PSP e `notification-gateway` não deve depender de identidade informada pela própria aplicação, como `Subscribe { ispb }`. Para que o gateway entregue notificações e aceite ACKs com segurança, a identidade do PSP precisa vir da conexão autenticada.

O modelo desejado é usar mTLS: o PSP apresenta um certificado de cliente, o `notification-gateway` valida esse certificado com uma CA confiável e a aplicação associa a identidade autenticada ao ISPB autorizado. A partir disso, o stream fica vinculado automaticamente ao ISPB autenticado.

**Tarefas**

- [ ] Proteger a conexão gRPC entre PSP e `notification-gateway` com mTLS.
- [ ] Fazer o PSP apresentar certificado de cliente ao abrir o stream gRPC.
- [ ] Fazer o `notification-gateway` validar o certificado do PSP usando a CA confiável.
- [ ] Definir como o certificado identifica o PSP e como essa identidade é vinculada ao ISPB.
- [ ] Associar automaticamente cada stream gRPC ao ISPB autenticado.
- [ ] Remover `Subscribe { ispb }` do contrato gRPC; o stream deve ser associado ao ISPB autenticado pelo certificado.
- [ ] Garantir que o `notification-gateway` envie para cada stream apenas deliveries destinadas ao ISPB autenticado.
- [ ] Garantir que o PSP só consiga dar ACK em deliveries destinadas ao próprio ISPB autenticado.
- [ ] Adicionar testes negativos para PSP sem certificado, certificado inválido e ACK de delivery de outro ISPB.

**Notas**

- O ISPB informado por mensagem ou payload não deve ser usado como identidade autenticada.
- O stream gRPC passa a ser autorizado pelo ISPB extraído do certificado do cliente.
- Esta task cobre apenas PSP -> `notification-gateway`; autenticação PSP -> `kafka-producer` continua no backlog de segurança.
