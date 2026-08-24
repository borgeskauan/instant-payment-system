# Entrega durável de notificações pelo Kafka

## Decisão

O Kafka é o log durável da fronteira `SPI → PSP`. O PostgreSQL continua
protegendo atomicamente o fato financeiro e a criação da notificação, mas não
mantém um segundo histórico de delivery nem o progresso do PSP.

```text
transação financeira do SPI
  ├─ pagamento / saldo / auditoria
  └─ INSERT notification_outbox
             │
             │ depois do commit
             ▼
       fila limitada em memória
             │
             ▼
 Kafka psp-notifications-v1 (7 dias)
             │
       ┌─────┴─────────────┐
       ▼                   ▼
 tailer do Gateway    leitura histórica
       │                   │
       └────── Pull ───────┘
                  │
                  ▼
                 PSP
```

Essa escolha remove do PostgreSQL o `delivery_index`, a reconciliação periódica
e o lifecycle de ACK/lease. Ela aceita explicitamente o custo operacional de
tratar o Kafka como armazenamento durável durante a janela contratada.

## Escrita no SPI

`notification_outbox` contém somente:

```text
communication_id
recipient_ispb
payload
created_at
```

O payload é construído uma vez e inserido na mesma transação do efeito
financeiro. Após o commit, o lote correspondente entra em uma fila limitada,
consumida por um único publisher.

O publisher:

1. publica o lote inteiro com `acks=all` e idempotência do producer;
2. aguarda a confirmação de todas as mensagens;
3. remove o lote da outbox somente depois dessas confirmações.

Falha parcial, confirmação inconclusiva ou falha ao apagar a outbox repete o
lote inteiro. Isso pode criar mensagens físicas duplicadas e não pode causar
perda da obrigação lógica.

Ao iniciar, o SPI drena toda a outbox antes de iniciar seus consumidores Kafka
de pagamentos. Não existe polling periódico da outbox durante a operação: o
caminho normal é o evento `AFTER_COMMIT`, e o startup recovery cobre o trabalho
que sobreviveu a uma queda do processo.

## Log de notificações

O tópico é `psp-notifications-v1`:

- exatamente 8 partições;
- chave `recipient_ispb`, portanto todas as notificações de um PSP permanecem
  na mesma partição enquanto essa topologia não mudar;
- `cleanup.policy=delete`;
- retenção de 7 dias;
- payload completo e header `notification.communication-id`;
- volume Kafka persistente no ambiente local.

O sufixo `v1` identifica a geração da topologia. Alterar a quantidade de
partições exige uma nova geração de tópico e uma migração explícita dos
cursores, em vez de reinterpretar offsets antigos.

## Pull e cursor

O PSP mantém o progresso durável. Cada chamada unary `PullNotifications`
informa o último cursor processado e recebe até 15 notificações. Cada item
carrega o payload opaco e seu `communication_id` lógico.

O cursor é opaco e autenticado por HMAC. Ele contém, internamente:

```text
PSP + geração do tópico + partição + último offset examinado
```

O PSP só persiste o novo cursor depois de processar duravelmente o lote
completo. Se cair antes disso, reapresenta o cursor antigo e pode receber as
mesmas mensagens novamente. A entrega é, portanto, at-least-once.

Offsets de mensagens de outros PSPs na mesma partição também são examinados e
podem avançar o cursor. Isso é seguro porque a chave mantém cada PSP em uma
única partição, e o Gateway filtra o destinatário antes de responder.

Um cursor anterior ao começo retido do log retorna `CURSOR_EXPIRED`. Um cursor
de outro PSP, partição, geração ou com assinatura inválida é rejeitado.

## Caminho rápido e histórico

Um consumer do Gateway acompanha as oito partições e guarda uma janela
contígua e limitada por partição em memória. O buffer contém todos os records
da partição, não apenas os de um PSP, para provar quais offsets foram
examinados.

Quando o buffer cobre o cursor, o Pull não consulta armazenamento externo. Em
restart, eviction ou lacuna, o Gateway faz `assign + seek` diretamente no
Kafka, filtra o PSP e responde àquele Pull. Não há reidratação ou cache recovery
separado; novos records consumidos pelo tailer voltam a tornar o caminho de
memória suficiente naturalmente.

Quando o cursor está no tail conhecido, o Gateway faz long polling. A chegada
de um novo record para o PSP acorda a chamada. Há no máximo um Pull em andamento
por PSP.

## Propriedades e falhas

| Situação | Resultado |
| --- | --- |
| rollback financeiro | a outbox também é revertida |
| commit e queda antes do evento em memória | startup recovery publica a row |
| ACK parcial ou inconclusivo do Kafka | o lote inteiro é repetido |
| Kafka confirmou e o delete falhou | o lote inteiro é repetido |
| Gateway reiniciou | o Pull lê o Kafka a partir do cursor |
| PSP caiu antes de persistir o cursor | recebe novamente o lote |
| mensagem física duplicada | mesmo `communication_id`; PSP trata idempotentemente |
| cursor saiu da retenção | falha explícita; recuperação operacional |

O `communication_id` é a identidade lógica. Partição e offset são posição de
transporte, não identidade de negócio.

## Limitações conscientes do MVP

- O ambiente local usa um broker/controller e replication factor 1. Ele valida
  o protocolo e o processo, mas não alta disponibilidade de broker, host ou
  volume.
- A topologia de 8 partições é fixa nesta geração.
- A recuperação normal cobre até 7 dias de indisponibilidade do PSP. Períodos
  maiores pertencem a disaster recovery, fora do protocolo operacional.
- O ingresso financeiro ainda não aplica admission control baseado na saúde do
  transporte. Uma indisponibilidade prolongada pode bloquear a fila do SPI ou
  impedir que ele cumpra novas obrigações; essa proteção é trabalho futuro.
- Não há compactação, archive tier, múltiplos Pulls por PSP ou cursor shards.
- Duplicatas físicas são esperadas; exatamente-once não é prometido.

## Motivo da escolha

O desenho anterior tentava combinar PostgreSQL como fonte durável,
`delivery_index`, memória e Kafka como fast path. Isso preservava a correção,
mas duplicava estado, exigia reconciliação e pressionava o mesmo PostgreSQL que
processa o domínio financeiro.

Kafka como log durável reduz essa pressão e deixa uma autoridade clara:

```text
PostgreSQL = atomicidade da criação da obrigação
Kafka      = histórico durável de delivery por 7 dias
PSP        = progresso durável de consumo
Gateway    = protocolo, filtro e aceleração em memória
```

Para produção distribuída, a próxima etapa é dimensionar brokers, replication
factor, `min.insync.replicas`, storage, monitoramento de retenção e disaster
recovery. Isso não altera o contrato de Pull definido aqui.
