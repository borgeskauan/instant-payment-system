# Simplificar PSP, DICT e Angular da reference demo

- [ ] Reduzir PSP, DICT e Angular ao estado mínimo necessário para demonstrar o happy path

## Objetivo

Manter uma reference demo pequena e reproduzível, sem transformar PSP, DICT ou Angular em produtos paralelos ao core benchmarkado. A ordem de prioridade é simplicidade por adesão ao objetivo, manutenção e somente depois performance.

## Gate A — aprovado

O PSP existe para simular pagador e recebedor, criar ou recuperar clientes e contas, cadastrar e listar chaves, consultar destinatário, iniciar o pagamento, aceitar automaticamente pagamentos recebidos e refletir outcomes finais de forma idempotente no saldo visual. Login não é autenticação; saldo local é uma projeção da demo; persistência local é efêmera; o preview existe somente para visualização e a execução volta a resolver a chave no PSP. Tratamento próprio de rejeições foi removido do escopo.

O DICT existe somente para cadastrar uma associação única entre chave e destinatário e resolvê-la por chave exata. Suporte amplo a tipos de chave, validadores CPF/CNPJ, timestamps e metadados não consumidos foram removidos do escopo. Chave desconhecida significa `404`; duplicada significa `409`.

## Gate B — aprovado

No PSP, substituir H2/JPA por estado em memória, remover dependências e camadas de passagem, substituir estado global por configuração tipada, usar o mesmo saldo inicial da demo no PSP e no core, substituir Feign pelo cliente do Spring, eliminar o handler vazio e impedir criação implícita de contas. Preservar PACS tipado, HTTP/2/mTLS, Pull gRPC e idempotência.

No DICT, substituir PostgreSQL/JPA/Flyway por um diretório concorrente em memória, remover ports, adapters e dependências sem função, manter DTOs mínimos e adicionar testes semânticos de cadastro, consulta, ausência e conflito.

Na segunda passagem do PSP, remover serviços e mappers de uso único, consolidar processamento e liquidação em um único serviço de outcomes, remover estados e motivos internos sem efeito observável, tornar o cadastro de chave imutável e restringir CORS à origem da demo.

## Resultado

- PSP reduzido de 97 para 68 arquivos Java de produção e de 3.349 para 2.432 linhas;
- DICT reduzido a 7 arquivos Java e 123 linhas de produção;
- PostgreSQL, JPA, Flyway, H2, Feign, estado global, camadas de passagem e modelagem interna sem efeito observável removidos da reference demo;
- launcher standalone removido; inicialização, certificados e provisionamento da demo agora possuem uma única fronteira no Docker Compose;
- saldo inicial centralizado por `DEMO_INITIAL_BALANCE` para provisionamento do core e projeção local do PSP;
- reset limpo documentado: PSPs efêmeros devem iniciar junto de um log de notificações vazio;
- contrato final do PSP reduzido para abertura de conta de demonstração e execução por chave, com nova resolução autoritativa no servidor;
- PSP reduzido novamente, de 68 para 66 arquivos Java de produção e de 2.432 para 2.331 linhas;
- Angular reduzido a quatro telas e 18 arquivos de aplicação, sem endpoint `/info`, falsa semântica de login, cache de recebedor, specs-túmulo ou harness de teste vazio;
- PSP passou a expor o histórico efêmero do cliente com estados `PROCESSING`, `SETTLED` e `REJECTED`; a UI acompanha o pagamento submetido por até 1,5 segundo e mantém os pagamentos recentes na conta;
- a demo agora apresenta somente Alice no PSP A e Bob no PSP B; ambos podem enviar e receber pagamentos, apenas a chave do Bob é provisionada, e a chave da Alice pode ser criada pelo usuário pela interface;
- as telas internas identificam o cliente e o PSP ativos, preservam uma largura adequada no desktop, aceitam envio por Enter e apresentam a instituição recebedora com nome e ISPB;
- 30 testes do PSP, 4 testes do DICT, build Angular, validação dos Composes e smoke end-to-end aprovados após a segunda passagem.

## Trabalho restante — contrato do PSP e Angular

- [x] Remover a tela Angular e o endpoint `/info`, pois não participam do fluxo demonstrado.
- [x] Manter o preview visual, mas fazer `/transfer/execute` receber `senderCustomerId`, `receiverPixKey`, valor e descrição, resolvendo novamente a chave no PSP.
- [x] Remover do Angular o cache do objeto completo do recebedor e não confiar em um `Party` devolvido pelo navegador.
- [x] Não introduzir `previewId`, armazenamento temporário, expiração ou lifecycle adicional.
- [x] Substituir a falsa semântica de login por localizar ou criar um cliente de demonstração, alinhando nomes de DTOs, serviços e textos visíveis.
- [x] Simplificar o estado Angular para signals diretos, removendo a combinação de `BehaviorSubject`, `toSignal` e `computed` com efeitos colaterais.
- [x] Preservar somente o polling necessário para que o saldo final apareça na interface, com responsabilidade e nome explícitos.
- [x] Remover specs Angular que apenas instanciam componentes ou serviços e o harness associado caso nenhum teste semântico dependa dele.
- [x] Consolidar `PspService` e `TransferRequestService` em uma fronteira coesa de transferência de saída, removendo DTOs e modelos intermediários sem função.
- [x] Fazer o `NotificationProcessor` classificar e materializar cada payload a partir de uma única leitura JSON.
- [x] Usar coleções simples no `PaymentStore` quando a sincronização externa já serializar todos os acessos.
- [x] Expor a visão local de pagamentos do cliente e usar o `paymentId`, não o saldo, para acompanhar o outcome final na UI.

Preservar DICT separado, dois PSPs efêmeros, criação e listagem de chaves, preview do recebedor, PACS tipado, HTTP/2 com mTLS, Pull gRPC com cursor, idempotência e atualização visual dos dois saldos.

## Critérios de conclusão

- PSP e DICT preservam somente o happy path aprovado e as garantias essenciais do protocolo;
- nenhuma persistência ou camada existe apenas por cerimônia arquitetural;
- saldo inicial local e fundos provisionados partem do mesmo valor;
- contas ausentes e chaves inválidas não são mascaradas por criação implícita ou erro interno genérico;
- o reset limpo documenta que o estado efêmero dos PSPs deve começar junto de um log de notificações vazio;
- o navegador não envia dados autoritativos do recebedor no execute;
- os nomes do contrato não apresentam criação de cliente como autenticação;
- nenhuma tela, endpoint, cache ou camada intermediária permanece sem contribuir para o happy path;
- histórico, tela de resultado e saldo apresentam a mesma visão local de pagamento do PSP;
- testes dos dois projetos, builds, Composes, smoke e `git diff --check` passam;
- nenhuma persistência, autenticação real ou modelagem de PSP de produção entra no diff.

## Validação pendente

- [ ] Validar manualmente o fluxo da aplicação Angular.
