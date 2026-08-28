# Consistência entre DICT oficial e cadastro local de chaves do PSP

Este trabalho pertence exclusivamente à reference demo. A demo atual garante somente o happy path sobre estado efêmero e reset coordenado por `docker compose -f demo/docker-compose.yml down -v`; persistência e reconciliação completas continuam fora do compromisso do core.

**Por que existe**

O DICT oficial não expõe uma API para listar todas as chaves Pix de uma pessoa ou conta. Ele resolve vínculos por chave, cria/atualiza/remove vínculos e oferece mecanismos de eventos/sincronização, mas a visão de "minhas chaves Pix" precisa ser mantida pelo PSP custodiante em uma base local própria.

No ambiente local, o DICT usa PostgreSQL persistido, enquanto os PSPs ainda usam H2 em memória. Depois de reiniciar os PSPs, uma chave Pix antiga pode continuar apontando no DICT para uma conta que não existe mais no PSP atual ou que não é a conta visível no frontend. Isso faz a transferência liquidar para um identificador antigo, causando confusão no teste manual.

Também existe um comportamento perigoso no PSP: buscar uma conta por `BankAccountId` pode criar uma conta nova automaticamente. Esse fallback mascara erro de consistência durante settlement, porque o crédito/débito pode ser aplicado em uma conta criada implicitamente em vez de falhar de forma explícita.

Persistir o banco local dos PSPs não é prioridade agora, porque o escopo principal dos testes continua sendo SPI/Kafka/DICT e não durabilidade do PSP. A estratégia preferida para o ambiente manual é reset coordenado do estado local junto com remoção da criação implícita de contas no PSP.

**Tarefas**

- [ ] Manter fidelidade à API oficial: não criar endpoint no DICT para listar chaves por pessoa ou conta.
- [ ] Tratar o cadastro local do PSP como a fonte para "minhas chaves Pix", e o DICT como fonte de verdade para resolver/registrar/remover uma chave específica.
- [ ] Definir contrato para chave Pix já existente: retornar `409 Conflict` ou tratar como idempotente quando já pertencer à mesma conta.
- [ ] Impedir que falhas de negócio do DICT, como chave duplicada ou chave inexistente, virem `500`.
- [ ] Garantir atomicidade prática no cadastro: o PSP só deve salvar a chave local depois de sucesso no DICT; falha no DICT não pode deixar chave local órfã.
- [ ] Definir fluxo de remoção/alteração de chave mantendo consistência entre DICT e cadastro local do PSP.
- [ ] Avaliar uso futuro de eventos/sincronização do DICT oficial para reconciliar a base local do PSP.
- [ ] Separar lookup de conta de criação de conta no PSP; settlement deve buscar conta existente e falhar se ela não existir.
- [ ] Remover criação automática de conta em `CustomerBankAccountJpaAdapter.findById` ou restringir esse comportamento apenas ao fluxo explícito de criação de cliente.
- [ ] Registrar erro claro quando uma notificação de settlement referenciar conta local inexistente.
- [x] Criar reset coordenado para o ambiente manual: limpar DICT junto com o estado efêmero dos PSPs ou recriar seed coerente depois do restart.
- [ ] Documentar que persistir banco local dos PSPs está fora do escopo atual.
- [ ] Adicionar teste para chave Pix apontando para conta inexistente no PSP: fluxo deve falhar de forma explícita e observável, sem criar conta implicitamente.
