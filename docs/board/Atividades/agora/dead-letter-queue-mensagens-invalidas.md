# Dead letter queue para mensagens inválidas

- [ ] Dead letter queue para mensagens inválidas

**Por que existe**

Mensagens inválidas, incompatíveis com o contrato ou impossíveis de processar não devem travar o consumer group nem contaminar o caminho quente. O fluxo precisa isolar esses casos em uma DLQ com metadados suficientes para diagnóstico, replay controlado ou descarte consciente.

**Tarefas**

- [ ] Definir política de DLQ para falhas de parse, validação, contrato incompatível e erro inesperado de processamento.
- [ ] Criar tópicos DLQ para requests e status reports, com convenção de nomes clara.
- [ ] Publicar na DLQ o payload original e metadados: tópico original, partição, offset, timestamp, consumer group, erro, stack resumida e serviço origem.
- [ ] Garantir que mensagens enviadas para DLQ não travem o consumer group principal.
- [ ] Expor métricas de DLQ: mensagens/sec, total por motivo, total por tópico e idade da mensagem mais antiga.
- [ ] Criar logs estruturados para envio à DLQ sem logar payload sensível completo por padrão.
- [ ] Definir processo de replay controlado a partir da DLQ.
- [ ] Criar teste automatizado para payload inválido: mensagem vai para DLQ, consumer continua e fluxo saudável não é afetado.
- [ ] Criar teste automatizado para contrato antigo/incompatível, como PACS bruto chegando no tópico interno protobuf.
- [ ] Documentar quando uma mensagem deve ser reprocessada, corrigida manualmente ou descartada.
