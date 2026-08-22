# Replay idêntico como no-op no SPI

- [ ] Remover a republicação de notificações causada por replay idêntico no SPI

## Contexto

A implementação inicial de idempotência permitiu que um replay idêntico de
`pacs.008`, enquanto o pagamento ainda estivesse em `WAITING_ACCEPTANCE`,
reemitisse ou reconstruísse a obrigação de notificação de aceite.

Esse comportamento perdeu sua função depois da introdução de duas garantias
específicas:

- o SPI persiste a obrigação de notificação na outbox, na mesma transação do
  estado que a originou;
- o `notification-gateway` persiste uma única delivery lógica por
  `communication_id` e repete a entrega até receber ACK.

Manter a reconstrução por replay cria uma segunda forma de recuperação e mistura
responsabilidades: uma nova entrada tenta reparar um efeito de saída cuja
durabilidade já pertence à outbox e ao gateway.

O PSP continua sendo uma simulação usada para exercitar o SPI. Esta task não
adiciona inbox, persistência ou outras garantias produtivas ao PSP.

## Objetivo

Tornar replays idênticos verdadeiros no-ops no SPI:

- replay idêntico de `pacs.008` encontra o mesmo pagamento e não cria novo
  efeito financeiro, auditoria de negócio ou obrigação na outbox;
- replay idêntico de `pacs.002` não cria nova transição, liquidação, auditoria
  de negócio ou obrigação na outbox;
- duplicatas divergentes continuam rejeitadas explicitamente;
- ausência de ACK continua sendo recuperada pelo redelivery da delivery original
  no `notification-gateway`.

Cada transição de negócio deve criar exatamente uma notificação lógica. Entregas
físicas continuam seguindo semântica `at-least-once`.

## Direção da mudança

- Remover da classificação de `pacs.008` o resultado usado exclusivamente para
  solicitar nova notificação para um pagamento idêntico já existente.
- Fazer com que somente a criação original do pagamento produza a obrigação de
  aceite.
- Não recriar a outbox quando ela estiver ausente ou tiver sido removida: replay
  de entrada não é mecanismo de reparo da saída.
- Preservar a classificação e rejeição de duplicata divergente.
- Preservar o comportamento já idempotente de `pacs.002` e protegê-lo por teste.
- Manter os replays de `pacs.008` e `pacs.002` nos workloads do load-tool, agora
  comprovando ausência de novos efeitos lógicos.
- Atualizar a documentação anterior que ainda descreve reconstrução ou reemissão
  de resposta por replay idêntico.

## Restrição de simplificação

A implementação deve reduzir a complexidade existente, não substituí-la por
outro mecanismo:

- resultar em redução líquida de código e ramificações relacionadas à
  republicação;
- não criar registry, estratégia, enum ou framework genérico de cenários de
  replay;
- não adicionar um mecanismo alternativo de reconstrução da outbox;
- manter separadas as responsabilidades de idempotência de entrada, produção
  atômica da outbox e redelivery do gateway.

Se `acceptanceRequests` passar a representar exatamente o mesmo conjunto de
`createdPayments`, avaliar sua remoção para evitar duas representações do mesmo
fato.

## Testes

- Replay idêntico de `pacs.008` em `WAITING_ACCEPTANCE` mantém uma única
  transação, uma única auditoria de criação e uma única row original na outbox.
- Replay idêntico de `pacs.008` não recria uma row da outbox removida
  artificialmente.
- Replay idêntico de `pacs.008` em estado avançado permanece no-op.
- Replay idêntico de `pacs.002` não altera status ou saldos e não cria auditoria,
  settlement ou outbox adicional.
- Duplicata divergente de `pacs.008` continua sendo classificada como conflito.
- Status divergente continua sendo rejeitado explicitamente.
- Delivery sem ACK volta a ser elegível após o vencimento da lease no gateway.
- Delivery com ACK persistido não volta a ser selecionada.
- O workload funcional com replay de ambos os PACS permanece válido.

## Critérios de aceite

- uma entrada original cria no máximo uma obrigação lógica por destinatário e
  transição;
- replay idêntico não produz nova transição, saldo, auditoria de negócio,
  settlement ou outbox;
- replay não é usado para reparar outbox ausente ou corrompida;
- duplicatas divergentes permanecem observáveis e rejeitadas;
- lost ACK é recuperado exclusivamente pelo mecanismo de lease e redelivery do
  `notification-gateway`;
- os testes caracterizam `pacs.008` e `pacs.002` com as novas expectativas;
- o smoke do workload misto com replay termina válido;
- a implementação remove mais código e ramificações do que adiciona;
- nenhuma persistência ou garantia nova é adicionada ao PSP simulado;
- a documentação apresenta a evolução arquitetural: a reconstrução por replay
  foi removida após outbox atômica e entrega confiável tornarem-na redundante.

## Fora de escopo

- alterar a semântica `at-least-once` de entrega física;
- garantir `exactly-once` fim a fim;
- implementar inbox durável no PSP;
- adicionar novos tipos de replay ou cenários de caos;
- criar recuperação operacional para corrupção ou remoção manual da outbox;
- tuning ou execução longa de performance.
