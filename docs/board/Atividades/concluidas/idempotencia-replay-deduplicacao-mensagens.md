# Idempotência, replay e deduplicação de mensagens

- [x] Idempotência, replay e deduplicação de mensagens

**Por que existe**

DLQ e reprocessamento só são seguros se as mensagens puderem ser reenviadas sem duplicar efeitos colaterais. Hoje a liquidação no SPI já tem uma base idempotente para `pacs.002`, mas o replay de `pacs.008` ainda pode falhar por conflito de chave no insert, e o PSP ainda pode aplicar confirmação duplicada no saldo local.

O objetivo é tornar o replay seguro ponta a ponta: duplicidade com o mesmo identificador e mesmo conteúdo deve reconstruir ou reemitir a resposta esperada; duplicidade com conteúdo divergente deve falhar de forma explícita e observável.

Replay a partir da DLQ entra aqui apenas como técnica de teste de idempotência. A ideia é publicar uma mensagem na DLQ, reenviar o payload preservado para o tópico de origem em um cenário controlado e provar que o sistema não duplica liquidação, status ou saldo. Isso não implica criar ferramenta ou processo operacional de replay da DLQ.

**Tarefas**

- [x] Tornar a gravação de `pacs.008` no SPI idempotente por `paymentId`/`EndToEndId`.
- [x] Para `pacs.008` duplicado com mesmo conteúdo, não criar nova transação e reemitir a notificação de aceite necessária.
- [x] Para `pacs.008` duplicado com conteúdo divergente no mesmo `paymentId`, tratar como conflito irrecuperável e encaminhar para erro/DLQ.
- [x] Manter a liquidação de `pacs.002` idempotente: replay de aceite/status não pode debitar fundos SPI mais de uma vez.
- [x] Tornar o PSP idempotente ao aplicar confirmações finais: `ACCEPTED_AND_SETTLED_FOR_SENDER` e `ACCEPTED_AND_SETTLED_FOR_RECEIVER` não podem debitar/creditar saldo local mais de uma vez.
- [x] Definir estado local no PSP para registrar confirmação já aplicada por `paymentId` e lado da confirmação.
- [x] Validar que request duplicada recebida pelo PSP recebedor pode reemitir `ACCEPTED_IN_PROCESS`, mas não sobrescreve dados divergentes sem erro explícito.
- [x] Adicionar testes para duplicidade de `pacs.008` com mesmo conteúdo e com conteúdo divergente.
- [x] Adicionar testes para replay de `pacs.002` já liquidado no SPI.
- [x] Adicionar testes para replay de notificação final no PSP sem alteração duplicada de saldo.
- [x] Validar manualmente cenário controlado de replay/idempotência ponta a ponta.

**Nota**

A automação ponta a ponta de duplicidade/replay, incluindo cenários no load-tool e reprocessamento de mensagens Kafka/DLQ, fica coberta pela task de backlog `Cenários realistas e reprocessamento no load-tool` em `docs/board/Backlog/operacao-testes.md`.
