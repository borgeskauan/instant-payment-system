# Cleanup — SPI

## Estado

O cleanup aprovado foi concluído e validado sem alteração do comportamento público, do schema ou do hot path físico.

| Gate A — negócio | Gate B — técnica | próxima ação |
| --- | --- | --- |
| aprovado | aprovado | concluído; nenhuma ação pendente nesta etapa |

## Objetivo essencial aprovado

Processar pagamentos Pix entre participantes, preservando autorização, idempotência, liquidez, estado financeiro, auditabilidade e a criação das notificações correspondentes.

## Inventário de negócio aprovado

| funcionalidade de negócio | contribuição ao objetivo | proposta |
| --- | --- | --- |
| admitir cada PACS.008 somente em nome do PSP pagador autenticado | impede movimentação financeira por participante não autorizado | manter |
| tratar replay idêntico como no-op e duplicata divergente como conflito | preserva uma única identidade e um único efeito por pagamento | manter |
| reservar saldo do pagador ou rejeitar imediatamente por insuficiência de fundos com AM04 | impede gasto acima da liquidez disponível | manter |
| produzir a notificação do pagamento admitido destinada ao PSP recebedor | cria a obrigação que permitirá ao recebedor conhecer o pagamento e produzir seu outcome | manter |
| aplicar uma única vez o PACS.002 autorizado do recebedor | conclui o pagamento sem crédito ou liberação duplicados | manter |
| creditar o recebedor quando aceito ou liberar o pagador quando rejeitado | preserva a movimentação financeira correspondente ao outcome | manter |
| produzir as notificações de outcome destinadas aos participantes corretos | cria as obrigações correspondentes ao resultado sem atribuir ao SPI a entrega ao PSP | manter |
| registrar fatos auditáveis da reserva, liquidação e rejeição na mesma transação do estado financeiro | permite reconstruir as decisões financeiras sem fatos parciais | manter |

Provisionamento e consulta administrativa de saldos, Kafka, JDBC, DLQ, batching, tracing e configuração não são funcionalidades de negócio. Eles serão avaliados como mecanismos técnicos somente depois do Gate A.

## Ambiguidades de negócio para aprovação

Nenhuma ambiguidade de negócio aberta foi identificada. O estado terminal `REJECTED` já preserva separadamente a origem da rejeição, e a fronteira de notificações já está definida: o SPI cria a obrigação; o Notification Gateway realiza a entrega.

## Trabalho anterior

O histórico e as evidências estão em [`Simplificação arquitetural do SPI`](../../concluidas/simplificar-arquitetura-spi.md). A classificação detalhada dos failure paths Kafka está em [`Tratamento de falhas`](../../../../topics/failure-handling.md).

## Baseline técnica

Na baseline de 28/08/2026, `./mvnw test` executou 200 testes sem falhas, erros ou skips. A suíte cobre PostgreSQL real, locks, concorrência, idempotência, rollback, auditoria, outbox, DLQ e configuração.

## Diagnóstico para o Gate B

### Complexidade essencial preservada

* os consumers Kafka decodificam, correlacionam registros e controlam ACK, retry e DLQ;
* as políticas puras de admissão, reserva e transição concentram as decisões de negócio fora do JDBC;
* os adapters JDBC mantêm SQL em lote, locks ordenados e atomicidade financeira;
* auditoria e outbox participam da mesma transação do pagamento;
* o pipeline da outbox retém obrigações até a confirmação do Kafka;
* JFR amostrado e o log da configuração efetiva permanecem diagnósticos pequenos e usados.

O tamanho das duas classes de persistência reflete principalmente SQL, arrays, row mapping, locks e coordenação transacional. Dividi-las apenas por quantidade de linhas aumentaria a navegação sem remover uma responsabilidade concreta.

### Ambiguidades técnicas encontradas

1. `PaymentTransactionCommand` significa uma requisição completa no ingresso, mas o fluxo PACS.002 fabrica uma instância parcial contendo somente ID, valor e ISPBs. Proposta: introduzir uma referência imutável e mínima ao pagamento para settlement, rejeição, auditoria e notificações de status; manter o command completo somente onde o payload PACS.008 é necessário.
2. `domain.services` contém tanto políticas puras quanto coordenação transacional e integração Spring. Proposta: manter políticas e fatos no domínio e mover os serviços de aplicação para um package `application`, sem criar uma hierarquia ou framework novo.
3. `JdbcFundsRepository` está dentro de `port.output`, embora seja uma implementação PostgreSQL. Proposta: manter `FundsRepository` como port e mover a implementação para `adapter.output`.
4. `NotificationPublication` está no adapter Kafka, embora represente a obrigação antes de Kafka e também seja persistida na outbox. Proposta: renomeá-la para `OutboundNotification` e colocá-la na fronteira de notificações, deixando Kafka apenas como mecanismo de publicação.
5. `Utils` é um nome genérico para a única operação de obter o ISPB de uma parte. Proposta: substituir o helper por operações explícitas no modelo do pagamento e removê-lo.

Os testes do SPI que inspecionam texto do Compose possuem ownership de infraestrutura. Eles serão tratados na etapa de infraestrutura; não serão removidos nesta intervenção sem cobertura substituta.

### Código morto, falhas e trabalho implícito

Nenhum código morto de runtime foi comprovado. A busca por `TODO`, `FIXME`, `HACK`, `XXX`, placeholders e branches incompletos não encontrou trabalho pendente no código de produção. Os retornos nulos encontrados pertencem à normalização explícita de campos opcionais.

Os failure paths permanecem como aprovados: entradas inválidas, não autenticadas, não autorizadas ou divergentes seguem para DLQ; indisponibilidade transitória do banco mantém retry sem ACK; rejeições financeiras esperadas seguem o domínio; falhas internas arbitrárias permanecem defeitos de batch tratados pelo error handler. Nenhuma alteração desse contrato é proposta.

## Intervenção executada no Gate B

1. Preservar todas as funcionalidades, invariantes, schema, contratos Kafka, configuração homologada e mecanismos de recuperação.
2. Substituir o `PaymentTransactionCommand` parcial por uma referência mínima ao pagamento, eliminando objetos incompletos no caminho PACS.002.
3. Corrigir os packages de ownership dos serviços de aplicação e do adapter JDBC de fundos, sem adicionar camadas genéricas.
4. Renomear e realocar o modelo de notificação para representar a obrigação, não o transporte Kafka.
5. Remover o helper `Utils` em favor de linguagem explícita do pagamento.
6. Atualizar somente os testes afetados e preservar os testes semânticos, de integração e de falha.
7. Não alterar SQL, batching, concorrência, tuning, schema ou comportamento público nesta etapa.

## Resultado e evidências

* `PaymentTransactionCommand` voltou a representar somente a entrada PACS.008 completa; settlements e rejeições posteriores usam `PaymentReference`, sem fabricar objetos parcialmente preenchidos.
* coordenação transacional, auditoria e criação de obrigações de notificação estão em `application`; políticas puras e fatos permanecem no domínio; JDBC, Kafka e Spring permanecem nos adapters.
* `JdbcFundsRepository` passou de port para adapter, enquanto `FundsRepository` permaneceu como contrato de saída.
* `NotificationPublication` passou a `OutboundNotification`, deixando explícito que a obrigação existe antes e independentemente do transporte Kafka.
* o helper genérico `Utils` foi removido e o acesso ao ISPB passou a usar linguagem explícita do pagamento.
* SQL, schema, batching, concorrência, tuning, contratos Kafka e failure paths não foram alterados.
* `./mvnw test` executou 197 testes sem falhas, erros ou skips após a intervenção final.
* `git diff --check` passou, a busca por packages e nomes antigos não encontrou resíduos e a busca por `TODO`, `FIXME`, `HACK` e `XXX` continuou vazia no código de produção.

A revisão final de performance encontrou serialização genérica de mapas no caminho de criação das notificações. Microbenchmarks mostraram redução de 35% a 75% nas alocações e de 26% a 49% no tempo de serialização ao usar payloads tipados. `NotificationPayloadFactory` passou a serializar records privados diretamente para `byte[]`; o wrapper genérico `NotificationContentSerializer` e seu teste orientado à implementação foram removidos. Os testes preservados protegem o JSON PACS externo e o rollback transacional, não a ausência da classe antiga.

Uma run longa qualificada não foi repetida para esta limpeza. O smoke integrado final exercitou a imagem real, PostgreSQL, Kafka, os dois outcomes de negócio e os replays sem violações.

## Próximas decisões

- [x] Definir o objetivo essencial de negócio proposto.
- [x] Aprovar o inventário de negócio e confirmar a ausência de ambiguidades abertas.
- [x] Confrontar o estado técnico já produzido com o escopo aprovado.
- [x] Aprovar o diagnóstico técnico e a intervenção proposta no Gate B antes de qualquer nova intervenção.
- [x] Executar a intervenção aprovada e validar a suíte completa e os resíduos estáticos.
