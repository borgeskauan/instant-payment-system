# Entrega híbrida de notificações com índice por PSP

## Decisão

Migrar a entrega de notificações para uma arquitetura híbrida na qual:

- o SPI persiste uma única notificação completa e imutável junto da transação
  financeira;
- o PostgreSQL é a fonte de verdade da existência da notificação;
- o Kafka permanece como fast path best effort entre SPI e Gateway;
- o Gateway persiste apenas a posição da notificação no fluxo do PSP;
- o caminho saudável de Pull usa um buffer efêmero em memória;
- o PostgreSQL responde diretamente quando a memória não contém o trecho
  solicitado;
- um reconciler garante que toda notificação durável receba uma posição, mesmo
  quando o fast path falhar.

A migração foi dividida em três fases. Kafka permaneceu ativo durante toda a
transição, e cada mecanismo antigo só foi removido depois que seu substituto
estava funcionalmente comprovado. A Fase 3 representa o estado atual.

## Motivação

O modelo anterior já oferecia Pull unary, cursor autenticado por PSP e entrega
at-least-once, mas mantinha duas cópias duráveis da notificação completa e ainda
tratava a publicação no Kafka como parte da correctness:

```text
SPI transaction
  ↓
notification_outbox completa e mutável
  ↓
publisher confiável + retry
  ↓
Kafka
  ↓
notification_delivery completa
  ↓
SELECT no PostgreSQL a cada Pull
```

Além da duplicação do payload, o modelo mantinha o lifecycle
`PENDING → PUBLISHED`, atualizava individualmente as notificações publicadas e
serializava globalmente a alocação de posições no Gateway.

Os diagnósticos de performance que motivaram a mudança observaram, numa
execução representativa:

```text
INSERT largo de notification_delivery    ≈ 51,4 s de SQL
PENDING → PUBLISHED na outbox             ≈ 37,8 s de SQL
WAL do update de publicação               ≈ 223 MiB
SELECTs usados pelo Pull                  ≈ 12,3 s de SQL
```

Esses tempos não são ganhos automaticamente somáveis. Eles identificam o
trabalho que cada fase pretende remover ou reduzir; o efeito end-to-end deve ser
medido após cada checkpoint.

## Pré-condição: congelar o protocolo Pull

As três fases preservam o contrato atual:

```text
PullNotifications(cursor)
→ resposta unary
→ no máximo 15 notificações
→ nextCursor vinculado ao PSP
→ at-least-once
```

Durante a migração não serão alterados o transporte, o tamanho máximo do lote,
a propriedade do cursor nem a semântica de redelivery. O limite 15 faz parte do
protocolo, não é uma configuração do PSP ou do profile de carga.

## Estado final

```text
                         PostgreSQL
              ┌──────────────────────────────┐
SPI ─────────►│ outbound_notification        │
              │  notificação completa       │
              │  imutável                   │
              │  fonte de verdade           │
              │                              │
Gateway ─────►│ delivery_index               │
              │  communication_id            │
              │  recipient_ispb              │
              │  delivery_position por PSP   │
              └──────────────────────────────┘
                    ▲                 ▲
                    │                 │
              reconciler       fallback do Pull
                    │                 │
SPI ──best effort──► Kafka ─────► Gateway RAM
                                      │
                                      ▼
                               PullNotifications
                                      │
                                      ▼
                                     PSP
```

### Fronteira de dados

O desenho pressupõe que `outbound_notification` e `delivery_index` estejam no
mesmo PostgreSQL lógico. Essa condição já existe no ambiente atual e permite que
fallback e reconciler façam o join diretamente.

O compartilhamento não altera a autoridade de escrita:

- o SPI pode inserir em `outbound_notification`, mas não escreve no índice;
- o Gateway pode ler `outbound_notification` e escrever `delivery_index`, mas
  não modifica a notificação;
- nenhuma das tabelas é atualizada pelo PSP.

Separar os bancos futuramente exigirá outra fronteira de replicação ou consulta
e constitui uma mudança arquitetural própria, fora destas três fases.

### `outbound_notification`

O SPI é o único writer. A notificação é criada na mesma transação das mudanças
financeiras:

```text
BEGIN

payment / settlement / audit
+
INSERT outbound_notification

COMMIT
```

A tabela contém uma única vez:

```text
communication_id
recipient_ispb
event_type
payment_id
notification_status
schema_version
payload
created_at
```

Depois do insert, a row é imutável. O significado persistido é somente:

> Esta notificação existe e deve eventualmente fazer parte do fluxo do PSP.

Não existem status de publicação, tentativas, retry timestamps ou confirmação
de entrega nessa tabela.

### `delivery_index`

O Gateway é o único writer. A projeção contém somente:

```text
communication_id      chave única
recipient_ispb
delivery_position
```

O significado persistido é:

> Esta é a posição da notificação no fluxo deste PSP.

`communication_id` impede indexação lógica duplicada. A combinação
`(recipient_ispb, delivery_position)` também é única.

As posições são locais ao PSP:

```text
PSP A: 1, 2, 3, 4...
PSP B: 1, 2, 3, 4...
```

Kafka partition e offset podem permanecer como metadata operacional, mas não
participam do cursor lógico e não são necessários para reconstruí-lo.

### `ensureIndexed`

Kafka e reconciler convergem para a mesma operação idempotente:

```text
Kafka ───────┐
             ├──► ensureIndexed(notification)
Reconciler ──┘
```

Para cada PSP, a operação:

1. adquire um advisory transaction lock derivado do ISPB;
2. ignora `communication_id` já indexado para o mesmo destinatário;
3. lê a última posição do PSP;
4. atribui posições consecutivas apenas às notificações novas;
5. insere o índice e confirma a transação;
6. somente depois do commit, acrescenta posição e payload ao buffer efêmero.

Notificações de PSPs diferentes não compartilham lock. Quando uma entrada já
existir, sua posição original não muda. O mesmo `communication_id` associado a
outro destinatário é uma violação de integridade, não um replay aceitável.

### Buffer efêmero

O Gateway mantém uma janela ordenada com até 150 notificações recentes por PSP.
Ela existe apenas para performance e pode ser perdida integralmente.

A RAM responde a `Pull(C)` somente quando contém uma sequência contígua
começando exatamente em `C + 1`. O lote pode conter menos de 15 notificações,
pois 15 é máximo e nunca mínimo.

Quando o cursor é anterior à janela, existe uma lacuna ou o Gateway reiniciou,
o Pull consulta:

```sql
SELECT index.delivery_position, notification.payload
FROM delivery_index AS index
JOIN outbound_notification AS notification
  ON notification.communication_id = index.communication_id
WHERE index.recipient_ispb = :recipientIspb
  AND index.delivery_position > :cursorPosition
ORDER BY index.delivery_position
LIMIT 15;
```

O resultado é devolvido diretamente ao PSP. Não existe uma etapa separada de
rehydration, prefetch ou reconstrução do buffer. Novos eventos Kafka ou do
reconciler voltam a alimentar a janela naturalmente.

Evicção da RAM não representa processamento pelo PSP. Um PSP que reutilizar um
cursor antigo continua recebendo o trecho correspondente pelo fallback SQL.

### Long polling

O Gateway registra o Pull ativo antes de verificar RAM e banco, evitando perder
um sinal entre a consulta e a espera. O fluxo é:

```text
admitir um Pull para o PSP
↓
tentar sequência contígua na RAM
↓ miss
tentar fallback no PostgreSQL
↓ vazio
aguardar sinal de ensureIndexed ou timeout
↓
verificar novamente e responder
```

No máximo um Pull pode estar ativo por PSP. "Zero SELECT" significa que nenhum
SELECT é necessário quando a janela em memória já contém o próximo trecho. Um
PSP sem backlog, com cursor antigo ou após restart ainda pode consultar o banco.

### Reconciler

O reconciler procura notificações existentes sem posição na fonte imutável
`outbound_notification`:

```sql
SELECT notification.*
FROM outbound_notification AS notification
WHERE notification.communication_id > :cycleCursor
  AND notification.created_at <= CURRENT_TIMESTAMP - INTERVAL '1 minute'
  AND NOT EXISTS (
      SELECT 1
      FROM delivery_index AS index
      WHERE index.communication_id = notification.communication_id
  )
ORDER BY notification.communication_id
LIMIT 1000;
```

Os resultados passam pela mesma `ensureIndexed` usada pelo Kafka. O reconciler
não publica no Kafka e não mantém um segundo lifecycle de retry. Sua execução é
idempotente: concorrência com o Kafka pode repetir a tentativa física, mas
produz exatamente um índice lógico.

No MVP, a varredura começa no startup e volta a ocorrer um minuto depois do fim
do ciclo anterior. O cursor acima existe somente em memória durante um ciclo,
para paginar sem recomeçar a busca a cada lote; todo novo ciclo volta ao início.
Falhas de um PSP não impedem os demais e são reconsideradas no ciclo seguinte.
Uma notificação só é elegível depois de completar um minuto. Essa janela deixa
o fast path Kafka terminar o trabalho normal antes que uma ausência transitória
seja tratada como falha; por isso a recuperação ocorre entre um e dois minutos
depois da criação, conforme a posição relativa ao próximo ciclo.

Esta escolha é deliberada. Kafka cobre o caminho saudável, portanto a
reconciliação deve normalmente encontrar zero rows e existe apenas para o
failure path raro. Aceitamos maior latência e um scan histórico pouco frequente
para não introduzir worklist, watermark ou outro lifecycle persistente. Uma
medição com aproximadamente `600 mil` rows já indexadas mostrou que provar a
ausência de lacunas exige varrer as duas relações. No diagnóstico limpo da Fase
2, quatro scans sem resultado consumiram `14,741 s` de SQL wall-time no total
(`3,685 s` em média e `8,418 s` no máximo). Esse custo já é visível e deve
continuar sendo observado; worklist ou batch watermark permanecem uma mudança
posterior, não antecipada no MVP.

## Fase 1: projeção mínima e RAM

### Objetivo

Remover a segunda persistência larga sem alterar ainda a garantia de transporte
SPI → Kafka.

```text
SPI
notification_outbox completa
  ↓
publisher confiável atual
  ↓
Kafka com payload completo
  ↓
Gateway
delivery_index mínimo + buffer RAM
  ↓
Pull
```

Nesta fase:

- `notification_outbox`, `PENDING/PUBLISHED`, retry e recovery permanecem;
- Kafka continua obrigatório para a notificação chegar ao Gateway;
- `notification_delivery` larga é substituída por `delivery_index`;
- a posição passa de global para local por PSP;
- o Kafka alimenta o buffer somente depois do commit do índice;
- cache miss usa `delivery_index JOIN notification_outbox` e responde
  diretamente.

O alvo principal é eliminar o insert da segunda cópia completa. O insert do
índice pequeno permanece e deve aparecer separadamente na medição.

### Checkpoint da fase 1

A fase está comprovada quando:

- o mesmo `communication_id` Kafka produz um único índice;
- posições são consecutivas e ordenadas dentro de cada PSP;
- PSPs diferentes podem indexar em paralelo;
- o buffer nunca expõe uma entrada antes do commit do índice;
- RAM e fallback SQL produzem o mesmo payload e o mesmo `nextCursor`;
- restart com RAM vazia continua entregando pelo fallback;
- o Pull preserva at-least-once e o limite 15;
- o benchmark demonstra o custo do índice pequeno em comparação ao insert largo.

## Fase 2: reconciler e independência de Kafka

### Objetivo

Provar que uma notificação durável no PostgreSQL é suficiente para entrega
eventual, antes de remover o publisher confiável.

Durante esta fase coexistem deliberadamente:

```text
publisher confiável atual
+
reconciler novo
```

Kafka continua no fast path. O reconciler encontra qualquer row de
`notification_outbox` sem `delivery_index`, indexa e alimenta o buffer após o
commit. Ele não filtra por `publication_status`: tanto uma row `PENDING` que
nunca chegou ao Kafka quanto uma row `PUBLISHED` que não chegou a confirmar o
índice precisam convergir.

### Checkpoint da fase 2

Os testes precisam provar:

```text
DB COMMIT
↓
Kafka nunca recebe
↓
reconciler cria o índice
↓
PSP recebe
```

```text
Kafka recebe
↓
Gateway cai antes do delivery_index COMMIT
↓
reconciler cria o índice
↓
PSP recebe
```

```text
Kafka e reconciler concorrem
↓
um único delivery_index lógico
↓
uma única posição no fluxo do PSP
```

Também devem ser cobertos restart do reconciler, repetição do mesmo scan,
falha parcial entre PSPs e continuidade da entrega enquanto Kafka estiver
indisponível.

A fase termina somente quando for possível afirmar:

> A eventual indexação e entrega não dependem da confirmação do Kafka.

## Fase 3: notificação imutável e Kafka best effort

### Objetivo

Remover o lifecycle de publicação que perdeu sua responsabilidade de
correctness.

**Estado: implementada.** O schema, o caminho depois do commit e as consultas
do Gateway já usam o modelo abaixo; o checkpoint de performance é registrado
separadamente no diário da tarefa de estabilização.

`notification_outbox` torna-se `outbound_notification`, mantendo o conteúdo
durável e removendo:

- `publication_status`;
- `attempt_count`;
- `next_attempt_at`;
- `last_error`;
- `published_at`;
- `updated_at` usado exclusivamente pelo lifecycle;
- índice de rows pendentes;
- polling e retry do outbox;
- updates `PENDING → PUBLISHED`.

Depois do commit, o SPI tenta publicar a notificação completa no Kafka. Falha,
timeout ou crash não alteram a row e não iniciam retry no SPI. O reconciler do
Gateway é responsável por descobrir o índice ausente.

O Kafka continua sendo valioso por manter o Gateway quente e evitar reads no
caminho saudável, mas deixa de ser necessário para impedir perda.

### Checkpoint da fase 3

A fase está comprovada quando:

- negócio commitado implica uma `outbound_notification` imutável;
- rollback financeiro não deixa notificação órfã;
- replay de negócio não cria uma segunda notificação lógica;
- falha antes, durante ou depois da tentativa Kafka converge pelo reconciler;
- não existem updates de publicação ou worker de recovery no SPI;
- a indisponibilidade de Kafka aumenta latência, mas não causa perda;
- o benchmark comprova a remoção do update de publicação e do WAL associado;
- todas as invariantes comprovadas nas fases 1 e 2 continuam válidas.

## Ownership final

```text
SPI
├─ negócio financeiro
├─ único writer de outbound_notification
└─ tentativa best effort de publicação Kafka

PostgreSQL
└─ fonte de verdade das notificações e dos índices

Kafka
└─ fast path de propagação, sem responsabilidade de correctness

Gateway
├─ único writer de delivery_index
├─ owner de delivery_position
├─ owner do buffer efêmero
├─ owner do reconciler
└─ owner do protocolo Pull

PSP
└─ owner do progresso durável por meio do cursor
```

Cada estado persistido possui um único significado:

```text
outbound_notification
= "esta notificação existe"

delivery_index
= "esta é a posição dela no fluxo deste PSP"
```

## Limites deliberados

Esta mudança não inclui:

- alteração do lote máximo 15;
- múltiplos Pulls concorrentes por PSP;
- cursor persistido ou checkpoint mantido pelo Gateway;
- rehydration ou prefetch de cache;
- retenção e garbage collection das notificações;
- substituição do gRPC;
- uso de Kafka partition/offset como cursor;
- otimizações adicionais do insert de `outbound_notification`;
- mudança dos recursos do ambiente de benchmark.

Depois da fase 3, o trabalho restante é cleanup, benchmark final e novo
profiling. Qualquer otimização posterior deve responder ao gargalo dominante
observado nesse novo estado, não ampliar antecipadamente esta arquitetura.
