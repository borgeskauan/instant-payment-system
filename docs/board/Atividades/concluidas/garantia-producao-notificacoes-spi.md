# Garantia de produção das notificações do SPI

- [x] Garantir a produção durável das notificações originadas no SPI

## Por que existe

O SPI confirmava alterações de pagamento e posições financeiras no PostgreSQL e só depois publicava as notificações no Kafka. Uma queda do processo ou indisponibilidade do Kafka entre essas operações podia deixar o fato financeiro confirmado sem que o `notification-gateway` jamais recebesse a obrigação de entrega.

O fluxo durável já existente no `notification-gateway` protege a etapa posterior, desde o consumo de `psp-notifications` até o ACK do PSP. A transactional outbox completa a garantia no trecho anterior, entre o fato financeiro no SPI e o Kafka.

## Solução implementada

O primeiro corte cria `notification_outbox`, com uma row por notificação e destinatário. Na mesma transação PostgreSQL, cada processamento executa:

1. o statement bulk financeiro/classificatório já existente;
2. um insert bulk das obrigações de notificação produzidas apenas pelos resultados efetivamente classificados.

Falha de validação, serialização ou insert da outbox desfaz pagamento, status e saldos. O consumidor só retorna e confirma o input Kafka depois do commit PostgreSQL, mas não espera a futura publicação da outbox.

As obrigações são:

| Fato classificado | Obrigação durável |
| --- | --- |
| Novo `pacs.008` ou replay idêntico ainda em `WAITING_ACCEPTANCE` | `ACCEPTANCE_REQUEST` para o recebedor |
| Transição efetiva para `REJECTED` | `REJECTED_NOTIFICATION/RJCT` para o pagador |
| Settlement efetivo | `SETTLED_NOTIFICATION/ACSC` para o pagador e `SETTLED_NOTIFICATION/ACCC` para o recebedor |

`communication_id` permanece a chave primária e mantém o algoritmo anterior. O insert usa `ON CONFLICT (communication_id) DO NOTHING`, inclusive para duplicatas no mesmo batch e replay. Resultados `NOOP`, divergentes ou não autorizados não criam novas obrigações.

## Conteúdo persistido e publicação

O payload de negócio é construído uma vez por obrigação, serializado com `ObjectMapper.writeValueAsBytes(...)` e persistido em `BYTEA`. O worker envia exatamente esses bytes; não relê o pagamento e não reconstrói conteúdo de negócio.

Tópico, key e headers não são persistidos porque são determinísticos:

- tópico: `psp-notifications`;
- key: `recipient_ispb`;
- headers: `communication_id`, `event_type`, `payment_id`, `schema_version` e `notification_status` quando presente.

O producer usa `StringSerializer` para a key, `ByteArraySerializer` para o payload e `acks=all`.

## Worker e garantias

O scheduler roda em todas as instâncias do SPI. A cada execução, cada instância seleciona em bulk até 1.000 rows `PENDING` vencidas, inicia todos os sends assíncronos e só então aguarda o batch. Sucessos e falhas são atualizados em conjuntos bulk separados.

Não há transação de banco aberta durante o acesso ao Kafka. Também não há ownership, claim, lease, token, lock, `SKIP LOCKED` ou coordenação entre workers. Duas instâncias podem selecionar e publicar fisicamente a mesma row. Essa duplicação é aceita pelo modelo `at-least-once`; o `notification-gateway` deduplica a delivery lógica por `communicationId`.

Uma row só vira `PUBLISHED` depois da confirmação do broker. Todos os updates usam `WHERE publication_status = 'PENDING'`; assim, uma falha atrasada não reabre uma row publicada. Falha no update após send bem-sucedido, queda durante leitura/envio ou reinício mantém a row `PENDING` e permite republicação segura.

Defaults configuráveis:

- batch: 1.000 rows por instância;
- `fixedDelay`: 20 ms;
- retry fixo: 1 segundo;
- tentativas ilimitadas.

A garantia entregue é: se o fato financeiro foi confirmado, a obrigação de notificação ficou durável e será publicada no Kafka pelo menos uma vez enquanto o Kafka e o PostgreSQL voltarem a ficar disponíveis. Não há garantia exactly-once. `PUBLISHED` significa somente confirmação do broker com `acks=all`, não ACK fim a fim do PSP.

## Replay

- Replay idêntico de `pacs.008` em `WAITING_ACCEPTANCE` tenta o mesmo `communicationId`.
- Row existente, `PENDING` ou `PUBLISHED`, é preservada sem alteração.
- Row ausente é recriada na outbox e será publicada somente pelo worker.
- Replay de `pacs.008` em estado avançado continua `NOOP`.
- Replay de `pacs.002` sem nova transição ou settlement não cria outbox.
- Não existe backfill para pagamentos anteriores à migration.

## Critérios verificados

- commit e rollback atômicos para novo pagamento, rejeição e settlement;
- rollback financeiro em falha de serialização ou persistência da outbox;
- indisponibilidade do PostgreSQL durante o insert da outbox preserva a exceção de infraestrutura, não confirma o batch de origem e entra em retry indefinido em vez de DLQ;
- insert e updates bulk;
- preservação byte a byte entre `BYTEA` e Kafka;
- tópico, key e headers determinísticos;
- espera da confirmação do broker antes de `PUBLISHED`;
- retry de falhas, recuperação após reinício e republicação após falha de update;
- seleção concorrente por múltiplos workers e terminalidade de `PUBLISHED`;
- separação bulk de sucessos e falhas;
- replay sem duplicação de obrigação lógica;
- deduplicação do gateway por `communicationId`, validada pelo consumo de duas publicações físicas contra a tabela real do PostgreSQL e pela existência de uma única delivery lógica.

Os testes `@SpringBootTest` do SPI usam PostgreSQL 17 efêmero via Testcontainers. Cada execução Maven aplica as migrations Flyway em um banco isolado em porta aleatória, sem reutilizar o PostgreSQL da stack de desenvolvimento. A suíte requer Docker disponível, mas não requer banco previamente iniciado.

A construção das obrigações foi consolidada em `NotificationObligationService`. O serviço produz uma lista plana, serializa cada payload uma única vez e executa um único insert bulk por batch financeiro, sem camadas intermediárias de agrupamento ou wrappers que escondam exceções JDBC.

O teste de deduplicação do `notification-gateway` também usa PostgreSQL 17 efêmero via Testcontainers. Ele entrega ao consumer dois records Kafka com offsets distintos e o mesmo `communicationId`, executa o `ON CONFLICT` do repositório de produção em commits separados e consulta a tabela para confirmar uma única row `PENDING` com os bytes originais.

## Evidência de carga

O run local `outbox-mvp/20260804_224546`, com alvo de 2.000 requests/s, aceitou 180.000 requests e terminou com 257.972 rows `PUBLISHED` e zero row `PENDING`. A asserção de drain da outbox passou.

O `notification-gateway` não concluiu todo o fluxo fim a fim dentro da janela de drain de 30 segundos desse run. Isso não altera a confirmação de publicação da outbox, mas evidencia que capacidade e backlog do trecho Kafka → gateway → PSP devem continuar sendo avaliados separadamente.

## Limitações Conscientes

Esta seção é parte obrigatória da feature e deve ser preservada.

- Várias instâncias podem selecionar e publicar fisicamente a mesma mensagem.
- A duplicação pode aumentar tráfego Kafka, uso de CPU e carga no `notification-gateway`.
- Não existe ownership de uma tentativa.
- Não existe claim, lease, fencing ou coordenação entre workers.
- `attempt_count` não representa uma contagem exata de todos os sends físicos concorrentes.
- `last_error` pode ser sobrescrito por outra falha concorrente e não representa necessariamente a última tentativa física global.
- `PUBLISHED` é terminal e não pode ser reaberto por uma falha atrasada.
- Rows `PUBLISHED` serão retidas indefinidamente neste corte.
- A tabela crescerá continuamente enquanto não houver retenção ou cleanup.
- Retry fixo pode gerar pressão durante indisponibilidades longas.
- Não existe estado `DEAD` nem limite de tentativas.
- Não haverá recuperação operacional automática para mensagens permanentemente inválidas.
- A observabilidade fica limitada a logs e consultas manuais da tabela.
- Não existe garantia exactly-once; duplicações físicas são parte da garantia `at-least-once`.
- Essas simplificações são adequadas para o MVP, mas não definem necessariamente o desenho final de produção.

## Sinais para Evolução

Esta seção é parte obrigatória da feature e deve ser preservada.

Adicionar coordenação, claim, lease, `SKIP LOCKED`, `claim_token`, retry mais sofisticado ou cleanup quando ocorrer pelo menos uma destas condições:

- crescimento relevante de publicações físicas duplicadas;
- aumento excessivo de tráfego Kafka causado pelas duplicações;
- aumento relevante de CPU, banco ou carga no `notification-gateway`;
- disputa frequente entre workers pelas mesmas rows;
- backlog crescendo mesmo com Kafka saudável;
- idade da row `PENDING` mais antiga crescendo continuamente;
- necessidade de garantir uma única tentativa ativa por row;
- necessidade de failover ou coordenação automática entre workers;
- necessidade de rolling deploy sem workers concorrentes sobre as mesmas rows;
- necessidade de diagnóstico preciso de cada tentativa;
- `attempt_count` ou `last_error` aproximados deixarem de ser suficientes operacionalmente;
- crescimento da tabela exigir retenção, particionamento ou cleanup;
- retry fixo causar tempestade de publicação durante indisponibilidades;
- surgir necessidade de estado terminal, tratamento operacional ou `DEAD`.
