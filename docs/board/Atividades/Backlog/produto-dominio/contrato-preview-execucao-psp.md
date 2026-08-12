# Contrato de preview e execução no PSP

**Por que existe**

Hoje o endpoint de execução de transferência recebe o objeto completo do recebedor retornado pelo preview. Isso reaproveita a consulta ao DICT e mantém o `/transfer/execute` rápido, mas acopla o cliente ao formato interno do recebedor. Uma alternativa melhor é o preview gerar um `previewId`, armazenar temporariamente os dados resolvidos no PSP pagador e fazer o execute receber apenas esse identificador, junto com os dados da transferência.

Os testes de carga atuais em `load-test/` chamam diretamente o fluxo SPI/Kafka por PACS e não dependem dos endpoints `/transfer/preview` e `/transfer/execute` do PSP.

**Tarefas**

- [ ] Fazer `/transfer/preview` retornar um `previewId` além dos dados exibíveis do recebedor.
- [ ] Armazenar temporariamente o resultado do preview no PSP pagador, com expiração.
- [ ] Alterar `/transfer/execute` para receber `previewId` em vez do corpo completo do `receiver`.
- [ ] Validar expiração, reuso e existência do preview antes de executar a transferência.
- [ ] Atualizar Bruno e REST Client para o novo contrato.
- [ ] Manter os testes de carga atuais compatíveis, já que eles exercitam SPI/Kafka diretamente.
