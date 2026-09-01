# Kafka como log durável de notificações

## Status

Decisão vigente no MVP. O [design canônico](../../design.md) descreve o fluxo atual; este registro explica por que PostgreSQL deixou de acompanhar cada entrega e quais custos foram aceitos ao promover Kafka a log operacional durável.

## Problema

A transação financeira e a publicação de sua confirmação não podem participar da mesma transação distribuída. O sistema precisa garantir simultaneamente que:

1. uma confirmação só exista quando a mudança financeira fizer commit;
2. uma queda entre PostgreSQL e Kafka não apague essa obrigação;
3. uma instituição possa retomar a entrega sem exigir exactly-once físico.

O primeiro desenho resolvia isso com reliable push: outbox, cópia larga em `notification_delivery`, claim, lease, ACK persistido e redelivery ativo. Era correto, mas duplicava payload e fazia o mesmo PostgreSQL responsável por dinheiro também acompanhar o lifecycle de cada entrega.

## Evolução medida

| Etapa | O que removeu | O que permaneceu |
| --- | --- | --- |
| persistência e ACK em lote | commits e chamadas pequenas | delivery, claim, lease e ACK |
| índice mínimo de delivery | segunda cópia larga do payload | reconciliação e posição persistida no Gateway |
| Pull com cursor | ACK individual, lease e redelivery ativo | backlog durável ainda dividido entre banco e memória |
| Kafka durável | índice, reconciler e histórico de delivery no PostgreSQL | outbox mínima para fechar a transação financeira |

Alguns resultados tornaram a mudança estrutural mais importante que novos ajustes locais:

- o índice mínimo reduziu o SQL do caminho de delivery em `67,06%` e o WAL em `48,75%`, mas não retirou o PostgreSQL da saturação;
- dez varreduras saudáveis do reconciler chegaram a `519,7 s` de SQL sem encontrar lacunas;
- remover `PENDING/PUBLISHED` eliminou `27,703 s` de SQL e cerca de `152 MB` de WAL do update de publicação;
- na comparação com o híbrido ordenado, Kafka durável reduziu o SQL exportado em `61,49%`, o WAL em `21,86%` e a CPU média do PostgreSQL em `22,006` pontos percentuais.

Esses números pertencem à campanha sequencial de estabilização, não a um único A/B contrabalançado. Eles demonstram a remoção de trabalho PostgreSQL; a capacidade final é sustentada separadamente pelas qualificações promovidas.

## Decisão

As autoridades ficam separadas:

```text
PostgreSQL = criação atômica da obrigação
Kafka      = histórico durável de entrega por 7 dias
PSP        = progresso durável de consumo
Gateway    = protocolo, filtro e aceleração em memória
```

Na transação financeira, a outbox guarda somente:

```text
communication_id
recipient_ispb
payload
created_at
```

Depois do commit, um publisher único envia o lote ao tópico `psp-notifications-v1` com producer idempotente e `acks=all`. A outbox só é removida quando todas as mensagens do lote são confirmadas.

Confirmação parcial, resultado inconclusivo ou falha ao apagar a outbox repetem o lote inteiro. Isso permite duplicata física e impede perda silenciosa da obrigação lógica.

## Log, Pull e cursor

O tópico usa oito partições, chave `recipient_ispb`, retenção de sete dias e `cleanup.policy=delete`. A topologia é versionada pelo sufixo `v1`: mudar a quantidade de partições exige novo tópico e migração explícita, pois offsets antigos não podem ser reinterpretados como uma sequência equivalente.

O PSP consulta `PullNotifications` com seu último cursor processado e recebe até 15 notificações. O cursor é opaco, autenticado por HMAC e vinculado ao PSP, à geração do tópico, à partição e ao último offset examinado.

O PSP só persiste o cursor novo depois de processar duravelmente o lote completo. Se cair antes disso, reapresenta o cursor anterior e pode receber mensagens novamente. A entrega é at-least-once.

`communication_id` é a identidade lógica da confirmação; partição e offset são apenas posição de transporte.

O Gateway acompanha as partições e mantém uma janela contígua em memória. Quando o buffer cobre o cursor, Pull não consulta armazenamento externo. Em restart, eviction ou lacuna, o Gateway faz `assign + seek` no Kafka e responde diretamente a partir do log. A memória acelera; não decide corretude.

## Falhas e recuperação

| Situação | Resultado |
| --- | --- |
| rollback financeiro | a outbox também é revertida |
| commit e queda antes de enfileirar/publicar | a row permanece na outbox |
| confirmação Kafka parcial ou inconclusiva | o lote inteiro pode ser repetido |
| Kafka confirmou e o delete falhou | o lote inteiro pode ser repetido |
| Gateway reiniciou | o Pull lê Kafka a partir do cursor |
| PSP caiu antes de persistir o cursor | o lote pode ser entregue novamente |
| cursor saiu da retenção | falha explícita e recuperação operacional |

Hoje, o SPI descobre rows antigas da outbox no startup. Durante o runtime, o fast path usa o evento pós-commit e o worker retenta o lote que já possui em memória. Uma obrigação que não alcançar essa fila pode depender de restart para ser redescoberta. A correção está isolada na task [Reconciliar a outbox de notificações em runtime](../../board/Atividades/Backlog/operacao-confiabilidade/reconciliar-outbox-notificacoes-runtime.md).

## Consequências e limites

- O ambiente qualificado usa um broker e replication factor 1; valida o protocolo, não alta disponibilidade do Kafka.
- A recuperação operacional cobre sete dias. Períodos maiores pertencem a disaster recovery.
- A topologia de oito partições é fixa nesta geração.
- Duplicatas físicas são parte do contrato; exatamente-once não é prometido.
- O ingresso financeiro ainda não aplica admission control baseado na saúde do transporte.
- Não há archive tier, compactação, múltiplos Pulls simultâneos por PSP ou cursor shard.

O ganho central foi reduzir autoridades sobrepostas: PostgreSQL continua protegendo o fato financeiro e sua obrigação de saída; Kafka passa a responder pela recuperação da confirmação depois da publicação.
