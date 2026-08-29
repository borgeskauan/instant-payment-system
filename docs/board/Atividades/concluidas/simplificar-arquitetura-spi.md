# Simplificação arquitetural do SPI

- [x] Concluir o cleanup arquitetural do SPI sem enfraquecer suas invariantes de negócio

## Propósito

Reduzir a complexidade cognitiva e operacional do SPI depois da estabilização
funcional e de performance do MVP. A simplificação parte do objetivo central do
componente, remove representações e mecanismos redundantes e mantém explícitas
as decisões que alteram domínio, persistência ou compatibilidade.

Este documento é um roadmap. Ele organiza as fases, registra as decisões já
fechadas e mantém explícitos seus gates. Cada fase de implementação deve receber
um plano próprio antes de alterar código.

## Contrato essencial preservado

O cleanup não pode enfraquecer estas responsabilidades do SPI:

* autenticar o PSP e autorizar sua atuação sobre o pagamento;
* admitir pagamentos de forma idempotente e rejeitar duplicatas divergentes;
* reservar, creditar ou liberar saldo atomicamente com a transição do pagamento;
* preservar as invariantes financeiras sob replay e concorrência;
* registrar os fatos mínimos de auditoria na mesma transação de negócio;
* criar atomicamente as obrigações de notificação;
* rejeitar entradas inválidas ou divergentes sem confirmar prematuramente o
  consumo Kafka;
* preservar evidências suficientes para diagnóstico funcional e de performance.

## Princípios de execução

* Simplificar primeiro removendo conceitos; só depois reorganizar o que restar.
* Não misturar limpeza estrutural com tuning de performance.
* Não trocar uma abstração redundante por um framework genérico.
* Uma representação deve ter um significado de negócio ou técnico único.
* Mudanças de domínio, schema e compatibilidade exigem decisão explícita.
* A atomicidade atual é uma invariante, não um detalhe de implementação.
* Cada fase deve reduzir código, dependências ou ambiguidades de ownership de
  forma observável.
* Otimizações de hot path só entram com evidência e medição próprias.

## Estado atual

### Fase 0A — auditoria orientada a fatos de negócio — concluída

Commit de referência: `ed05bb2`.

A auditoria deixou de reproduzir cada alteração técnica e passou a persistir os
fatos consolidados `PAYMENT_RESERVED`, `PAYMENT_SETTLED` e
`PAYMENT_REJECTED`. Reserva, settlement, rejeição, saldo e obrigação de saída
continuam atômicos.

A definição vigente está em
[`auditoria-transacoes-spi.md`](../concluidas/auditoria-transacoes-spi.md).

### Fase 0B — limpeza segura e infraestrutura residual — concluída

Commit de referência: `0eb83ea`.

Foram removidos:

* gravador CSV, endpoint, fila, writer e lifecycle próprios do SPI trace;
* configurações e flags exclusivas desse trace;
* publishers e recoverers duplicados de DLQ;
* scheduler e customização Netty sem consumidores ou benefício medido;
* hook de fault injection exposto na aplicação;
* MapStruct e Springdoc WebMVC sem uso;
* overloads, métodos de mapper e APIs de notificação não utilizados.

Os seis checkpoints semânticos continuam disponíveis como eventos JFR com
amostragem determinística. DLQ, ACK Kafka, transações financeiras, auditoria e
notificações mantiveram o comportamento caracterizado pelos testes.

## Fases do roadmap

### Fase 1 — separar a linguagem de status e motivos — concluída

Commits de referência: `250127b` e `8336cab`.

#### Problema atual

`PaymentStatus` representa simultaneamente três conceitos:

* estado persistido do pagamento, como `WAITING_ACCEPTANCE`,
  `ACCEPTED_AND_SETTLED` e `REJECTED`;
* status recebido no PACS.002, como `ACCEPTED_IN_PROCESS`;
* status produzido para notificações de pagador e recebedor, como
  `ACCEPTED_AND_SETTLED_FOR_SENDER` e
  `ACCEPTED_AND_SETTLED_FOR_RECEIVER`.

Isso permite combinações sem sentido e obriga persistência, protocolo e payload
de saída a dependerem do mesmo enum. Também coexistem `Reason`, que representa
motivos recebidos pelo protocolo, e `PaymentRejectionReason`, que representa o
motivo de negócio persistido pelo SPI.

#### Decisões fechadas

1. O SPI mantém a rejeição originada pelo PSP recebedor. Um pagamento reservado
   pode receber PACS.002 `RJCT`, voltar para `REJECTED` e liberar exatamente uma
   vez o valor reservado para o pagador.
2. Estado persistido, outcome recebido e status de notificação serão tipos
   distintos. A tradução ocorre somente nas fronteiras de entrada e saída.
3. O estado persistido possui apenas conceitos internos, como
   `WAITING_ACCEPTANCE`, `SETTLED` e `REJECTED`.
4. PACS.002 recebido representa um outcome externo aceito ou rejeitado; ele não
   compartilha enum com o estado persistido.
5. As notificações continuam distinguindo externamente `ACSC` para o pagador e
   `ACCC` para o recebedor. Ambos derivam do mesmo estado interno `SETTLED`.
6. O código padronizado do reason recebido é semântico e deve ser preservado. A
   descrição livre não participa da regra de negócio nem da identidade do
   replay.
7. PACS.002 `RJCT` originado pelo recebedor sem ao menos um reason code válido é
   entrada inválida. Ele não adquire locks, não altera pagamento ou saldo e não
   produz auditoria ou obrigação de notificação.
8. `INSUFFICIENT_FUNDS` permanece uma causa interna gerada pelo SPI e produz
   `AM04`; ela não é inferida silenciosamente de uma rejeição externa.
9. Um replay de PACS.002 é idêntico quando preserva pagamento, PSP autenticado,
   status e os mesmos reason codes normalizados. Mudança de status ou código é
   divergência; mudança apenas na descrição livre não é.

#### Direção de implementação

Usar um tipo por responsabilidade e concentrar a tradução nas fronteiras de
entrada e saída. Reasons externos não podem ser descartados ou convertidos
silenciosamente em causas internas. A implementação deve evitar uma hierarquia
genérica de statuses ou reasons: são poucos contratos fechados, modelados
diretamente.

#### Gate de saída

* [x] tabela explícita de traduções entre protocolo, domínio e notificação;
* [x] conjunto fechado de transições válidas;
* [x] semântica aprovada para rejeição originada pelo PSP recebedor;
* [x] testes de replay, divergência, autorização e concorrência preservados;
* [x] estado, saldo, auditoria e outbox permanecem na mesma transação.

#### Resultado

O runtime agora usa `PaymentState` somente para o estado persistido,
`StatusReportOutcome` para a entrada PACS.002 e `NotificationStatus` para a
saída. Causas internas e reason codes externos são persistidos separadamente;
descrições livres são descartadas na entrada e não participam da identidade de
replay.

A migration V18 preserva o histórico anterior e recusa estados que não possam
ser convertidos para o vocabulário fechado. Rejeição externa sem reason code é
invalidada antes do acesso ao repositório. Replays concorrentes idênticos
continuam produzindo uma única transição financeira.

Validação concluída em 27/08/2026:

* suíte completa do SPI com PostgreSQL/Testcontainers;
* `mixed-outcomes-smoke` pelo runner público, com 1.040 outcomes `ACSC`, 260
  outcomes `RJCT/AM04`, nenhum outcome ausente ou contraditório e nenhuma
  violação de replay.

### Fase 2 — adotar JDBC como fronteira de persistência — concluída

Commit de referência: `1b1465e`.

#### Problema atual

O adaptador chamado `JpaAdapter` instancia implementações baseadas em
`JdbcTemplate`. A classe JPA `Entity` funciona principalmente como estrutura de
leitura de rows, enquanto os hot paths usam SQL explícito, arrays PostgreSQL,
locks e updates em bulk. O projeto paga por Spring Data JPA/Hibernate sem usar
repositories JPA para esses fluxos.

#### Decisão fechada

JDBC puro será a implementação autoritativa. As invariantes dependem de SQL
explícito, ordem de locks e operações em bulk; não há intenção de reconstruir
esses fluxos com repositories JPA.

#### Direção de implementação

* substituir a entidade JPA por um modelo interno de row;
* remover dependências, annotations e bootstrap JPA/Hibernate sem função;
* renomear `JpaAdapter` e os modelos conforme a responsabilidade real;
* manter `PaymentTransactionRepository` coeso enquanto uma divisão não remover
  uma responsabilidade concreta;
* não duplicar no serviço de domínio a classificação que precisa ocorrer sob os
  locks e a transação do adaptador JDBC.

#### Gate de saída

* uma única stack de persistência no runtime;
* nomes coerentes com a tecnologia realmente usada;
* ausência de modelos paralelos sem responsabilidade própria;
* SQL, lock ordering, idempotência e rollback preservados por testes com
  PostgreSQL real;
* nenhuma regressão material nos caminhos PACS.008 e PACS.002.

#### Resultado

`JdbcTemplate` é a única stack de persistência. Foram removidos Spring Data
JPA/Hibernate, annotations de entidade e nomes que sugeriam uma implementação
JPA inexistente. Classificação, locks, operações em bulk e rollback continuam
protegidos pelos testes PostgreSQL.

### Fase 3 — criar um baseline atual do schema — concluída

Commit de referência: `58b3583`.

#### Problema atual

As migrations V1–V17 registram toda a evolução experimental do MVP, incluindo
buckets removidos, modelos de outbox substituídos e estruturas posteriormente
compactadas. Esse histórico é útil para compreender a evolução, mas aumenta o
custo de leitura e teste do estado final.

#### Decisão fechada

O MVP pode assumir um banco novo e terá um baseline que cria diretamente o
schema vigente. Não haverá contrato de upgrade executável desde V1.

A consolidação só acontece depois das fases de domínio e persistência, evitando
criar um baseline que precise ser refeito logo em seguida.

#### Preservação da evolução arquitetural

Antes de substituir V1–V17 pelo baseline, será criado um documento de evolução
arquitetural voltado ao portfólio. Ele registrará:

* arquitetura e schema iniciais;
* gargalos ou limitações observados;
* experimentos e evidências relevantes;
* alternativas avaliadas;
* decisões tomadas e mecanismos posteriormente removidos;
* invariantes do desenho final;
* referências aos commits, migrations e documentos históricos.

O Git continuará preservando os SQLs anteriores. O documento preservará a
narrativa e tornará a evolução compreensível sem exigir a leitura do histórico
inteiro de commits.

#### Gate de saída

* política de compatibilidade documentada;
* criação limpa do schema validada em PostgreSQL real;
* o baseline é a primeira versão suportada; upgrades desde V1–V17 não fazem
  parte do contrato;
* nenhuma migration consolidada antes da estabilização do modelo final.

#### Resultado

As migrations experimentais foram substituídas por um único baseline suportado
para bancos novos. A evolução arquitetural permanece documentada em
[`spi-schema-evolution.md`](../../../architecture/spi-schema-evolution.md) e no
histórico Git, sem obrigar o runtime a reconstruir modelos descartados.

### Fase 4 — manter a API administrativa de fundos — decisão concluída

`FundsAdminController` permanece no SPI, no package administrativo e sob
`/internal/funds`, sempre disponível no runtime atual. Não será criado profile,
feature flag, porta separada ou serviço adicional apenas para escondê-lo.

Isso é uma limitação consciente do MVP local:

* o endpoint usa a mesma porta do SPI;
* não possui autenticação;
* o Compose publica essa porta no host;
* `resetIfExists` permite substituir um saldo existente.

O prefixo `/internal` comunica intenção, mas não constitui isolamento de rede ou
segurança. Um deployment produtivo deverá adicionar autenticação, autorização e
restrição de exposição adequadas. Esse hardening não faz parte do cleanup nem
será antecipado por um profile que poderia ser habilitado incorretamente em
produção.

Nenhuma mudança de código é necessária nesta fase. O preparador continua usando
a API administrativa, sem consultar diretamente o schema PostgreSQL e sem
duplicar regras financeiras em scripts.

### Fase 5 — centralizar configuração runtime — concluída

Commit de referência: `38e6a30`.

Esta fase já possui task própria:
[`centralizar-configuracao-runtime.md`](centralizar-configuracao-runtime.md).

Ela deve acontecer depois da remoção das dependências e propriedades obsoletas,
para que somente configurações ainda necessárias sejam centralizadas. Este
roadmap não duplica seu inventário nem sua decisão de fonte autoritativa.

`application.yml` agora contém o baseline comportamental homologado. Properties
tipadas substituem fallbacks em `@Value`; Compose mantém apenas concerns de
deployment. O SPI emite `event=spi_runtime_configuration`, e o preparador copia
essa configuração efetiva para `inputs/spi-runtime-config.log` em todo diretório de resultado.

### Fase 6 — acabamento estrutural — concluída

Commit de referência: `b482ac4`.

Somente depois das decisões anteriores:

* alinhar packages e nomes às responsabilidades finais;
* remover DTOs, mappers, annotations, dependências e configurações que se
  tornarem comprovadamente mortos;
* revisar comentários que descrevem arquiteturas anteriores;
* atualizar guias ativos sem reescrever documentos históricos de experimentos;
* reduzir fixtures e helpers de teste duplicados sem reduzir cobertura
  semântica;
* executar uma última busca por APIs sem consumidores e defaults concorrentes.

Esta fase não cria novas camadas. Se uma reorganização não remover ambiguidade
ou dependência, ela fica fora do cleanup.

#### Resultado

Foram removidos o DTO/mapper paralelo de pagamentos, 14 colunas nunca
consultadas pelo fluxo JDBC e testes que apenas detectavam a ausência de APIs ou
schemas antigos. O schema atual, inserts reais, idempotência, transições,
auditoria e outbox continuam cobertos por testes semânticos e de integração.

### Fase 7 — validação final — concluída

Cada fase mantém seus testes focados e a suíte completa do SPI. Ao final:

1. executar todos os testes do SPI, incluindo integrações PostgreSQL e Flyway;
2. executar os testes funcionais do load-test que exercitam happy path,
   insufficient funds e replays;
3. executar um smoke curto com stack recriada;
4. confirmar outcomes e artefatos do smoke sem transformar o cleanup em uma
   nova rodada de estabilização de performance;
5. atualizar a documentação arquitetural com o estado final, não com as
   alternativas descartadas.

#### Resultado

Validação concluída em 28/08/2026:

* 177 testes do SPI, incluindo PostgreSQL/Flyway, sem falhas;
* suíte Rust e todos os testes shell do load-test sem falhas;
* stack recriada exclusivamente por `prepare-performance-environment.sh`;
* `mixed-outcomes-smoke` em
  `spi-cleanup-smoke/20260828_000528`: 1.050/1.050 originais ativos,
  rolling mínimo 103 para piso 100, 1.040 outcomes `ACSC`, 260 outcomes
  `RJCT/AM04`, replays PACS.008 65/65, replays PACS.002 51/51 e zero
  violações;
* latência p99 de 283,702 ms, dentro do threshold de 1 s do smoke;
* configuração runtime efetiva presente nos artefatos da execução.

Por decisão de escopo, não foi executada uma nova run de 15 minutos. O cleanup
foi validado funcionalmente; estabilização e regressões longas continuam sendo
trabalho de performance separado.

## Trabalhos relacionados, mas independentes

Estes itens não devem aumentar o escopo das fases acima:

* auditoria de entradas recusadas:
  [`auditoria-rejeicoes-entrada.md`](../Backlog/operacao-testes/auditoria-rejeicoes-entrada.md);
* replay idêntico como no-op:
  [`replay-identico-como-noop-spi.md`](replay-identico-como-noop-spi.md);
* homologação multi-instância:
  [`homologar-execucao-multi-instancia.md`](../Backlog/operacao-testes/homologar-execucao-multi-instancia.md);
* tuning adicional de throughput, CPU, memória ou latência;
* HA de Kafka/PostgreSQL e disaster recovery;
* mudanças no load-tool que não sejam exigidas para preservar seus workloads.

As tasks relacionadas devem ser revisadas contra a arquitetura vigente antes
de implementação. Em particular, documentos antigos que ainda mencionem ACK,
lease ou redelivery push do gateway não são autoridade sobre o modelo atual.

## Ordem de trabalho

```text
Fase 0 concluída
      ↓
Fase 1 — domínio
      ↓
Fase 2 — persistência
      ↓
Fase 3 — schema
      ↓
Fase 4 — decisão administrativa documentada; sem implementação
      ↓
Fase 5 — configuração
      ↓
Fase 6 — acabamento
      ↓
Fase 7 — validação final
```

Uma fase pode revelar que a mudança proposta não reduz complexidade ou não tem
benefício suficiente. Nesse caso, a decisão correta é documentar a manutenção
do desenho atual e avançar, não forçar uma refatoração.

## Critérios de conclusão

* cada tipo central possui um significado único;
* o SPI usa uma única estratégia de persistência deliberada;
* a política de migrations é explícita e testada;
* as limitações da API administrativa do MVP estão explícitas;
* configurações efetivas não dependem de defaults duplicados invisíveis;
* não existem mecanismos próprios quando a plataforma já fornece a mesma
  capacidade com menor ownership;
* o código final preserva atomicidade, idempotência, auditoria e entrega de
  notificações;
* a suíte funcional e o smoke em stack limpa permanecem verdes; regressões
  longas pertencem à estabilização de performance.
