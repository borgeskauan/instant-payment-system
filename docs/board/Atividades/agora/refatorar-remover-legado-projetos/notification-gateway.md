# Cleanup — Notification Gateway

## Estado

| Gate A — negócio | Gate B — técnica | estado | próxima ação |
| --- | --- | --- | --- |
| aprovado | aprovado | cleanup e simplificação do tailer validados | revisar o diff e encerrar a etapa |

## Objetivo essencial

Garantir que cada PSP obtenha, com isolamento e sem perda silenciosa, as notificações de pagamento destinadas a ele, retomando o consumo após interrupções dentro da janela operacional suportada.

## Inventário de negócio aprovado

| funcionalidade de negócio | decisão |
| --- | --- |
| entregar notificações somente ao PSP destinatário | manter |
| preservar conteúdo e identidade lógica produzidos pelo SPI | manter |
| garantir entrega at-least-once com retomada sem lacunas | manter |
| permitir consumo no ritmo do PSP | manter |
| manter notificações recuperáveis durante sete dias | manter |
| rejeitar progresso inválido, adulterado ou expirado sem criar lacuna silenciosa | manter |

Ownership aprovado: o SPI decide quais notificações existem e seu significado; o Gateway entrega payloads opacos; o PSP mantém progresso durável e idempotência.

## Intervenção técnica aprovada

* manter mTLS, Pull unary, progresso opaco assinado, log durável, long polling, janela recente e leitura histórica;
* manter a topologia fixa da geração vigente e tratar mudança de partições como migração explícita;
* remover assignor explícito, quantidade de partições configurável, constantes duplicadas e fechamento prematuro de Pull;
* manter `TreeMap`: array circular foi mais rápido no microbenchmark, mas o ganho absoluto não justificou substituir coleção padrão por indexação própria;
* simplificar o tailer removendo validação duplicada, agrupamento, ordenação, cópias intermediárias, atomicidade fictícia do cache e o estado redundante `KNOWN_TAIL`.

As ambiguidades relevantes de ownership, progresso e sequência foram resolvidas durante os dois gates; nenhuma ambiguidade permanece aberta nesta etapa.

## Evidências

* 37 testes do Gateway passaram sem falha;
* o Compose permaneceu válido;
* `git diff --check` passou.

A arquitetura de entrega vigente está documentada em [`Entrega durável de notificações pelo Kafka`](../../../../architecture/kafka-durable-notification-delivery.md).
