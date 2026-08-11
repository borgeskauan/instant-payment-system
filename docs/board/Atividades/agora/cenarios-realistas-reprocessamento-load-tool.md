# Matriz funcional de workloads para estabilização de performance

- [ ] Preparar a matriz funcional de workloads para estabilização de performance

## Objetivo

Preparar e validar funcionalmente os workloads que serão usados depois pela task **Estabilizar teste de carga dentro do budget de CPU**, em [`operacao-testes.md`](../../Backlog/operacao-testes.md).

A matriz cobre caminho feliz, resultados de negócio mistos, tráfego hot-pair e repetições idênticas de `pacs.008` e `pacs.002` aplicadas dentro dos workloads normais. Repetição é uma dimensão do workload, não um cenário de negócio nem um workload independente.

Para o handoff de performance, a meta contratual de `2.000 TPS` significa `2.000` pagamentos originais iniciados por segundo. Replays de pagamentos e repetições de status são carga adicional: não substituem originais para manter a taxa nominal e devem ser medidos separadamente do ingresso total.

Esta é uma task guarda-chuva e avança uma fatia por vez. Ela prepara workloads funcionalmente corretos e reproduzíveis, mas não faz tuning, runs longos de performance nem define gates finais de CPU, memória, latência ou throughput. Evidências de capacidade ou degradação encontradas aqui devem ser entregues à task de estabilização.

## Estado atual

- `uniform-smoke` é o baseline sem replay: caminho feliz, `2.000` pagamentos originais/s, `1m` de warmup, `1m` de janela ativa, `30s` de drain, 10 pares quentes, 40 frios e `80%` do tráfego nos pares quentes;
- `mixed-outcomes-smoke` é o workload funcional curto: 100 pagamentos originais/s, `5s` de warmup, `10s` de janela ativa e `10s` de drain;
- `mixed-outcomes-smoke` distribui deterministicamente cada bloco completo de 100 originais em `80%` happy-path e `20%` insufficient-funds, com participantes isolados e provisionamento declarado por cenário;
- a divisão `80/20` é funcional, não uma estimativa final de produção, e deverá ser refinada antes do handoff para estabilização;
- happy-path espera HTTP `2xx` e notificação ao pagador `pacs.002 ACSC`; insufficient-funds espera HTTP `2xx` e notificação `pacs.002 RJCT` com reason `AM04`;
- notificações ao pagador usam semântica `at-least-once`: uma ou mais entregas compatíveis são válidas, ausência ou outcome contraditório é violação;
- `targetTxRate` controla somente pagamentos originais; replays são agendados como carga adicional;
- `mixed-outcomes-smoke` seleciona deterministicamente `10%` dos originais para uma única retransmissão `pacs.008` idêntica, iniciada `10s` após o começo da tentativa original e independentemente de sua resposta HTTP;
- o replay `pacs.008` já passa pelo ingresso normal `/transfer`, com o mesmo pagador e mTLS, sem publicação direta no Kafka;
- `starts.csv` registra quais originais foram selecionados, `replays.csv` registra as tentativas repetidas e o relatório separa originais, replays e ingresso total;
- o relatório valida outcomes de negócio e replays no run inteiro; throughput, latência e SLA permanecem restritos à janela ativa;
- repetição deliberada de `pacs.002` ainda não está configurável nem é gerada pelo load-tool; esse é o próximo trabalho, na Fatia 2B;
- o run de 2026-08-09 (`observable-pacs002-outcomes-smoke/20260809_233329`) terminou com 1.251 originais aceitos e notificados, zero violações, `ACSC` nos 1.001 happy-path e `RJCT`/`AM04` nos 250 insufficient-funds;
- o run de 2026-08-11 (`pacs008-replay-functional-smoke/20260811_020918`) manteve 100 originais/s na janela ativa, aceitou os 1.251 originais e os 126 replays selecionados e terminou com zero violações de replay ou outcome; o menor atraso observado foi `10,000038s`;
- a SPI persiste falta de liquidez como `REJECTED / INSUFFICIENT_FUNDS`; settlement, saldos, auditoria, outbox e atomicidade permanecem cobertos pelos testes focados da SPI, sem consultas PostgreSQL no load-test.

## Fatia 0 — Contrato e execução reproduzível (concluída)

**Resultado:** perfis selecionáveis descrevem a execução e produzem um plano resolvido, sem caminhos arbitrários, seed, tipos implícitos ou alocação manual de participantes.

- [x] Selecionar perfis por nome em runner, simulador e relatório, usando `uniform-smoke` por padrão.
- [x] Resolver nomes internamente para `profiles/<name>.json` e rejeitar nomes inválidos, perfis ausentes ou JSON malformado antes de efeitos colaterais do runner.
- [x] Manter a validação semântica autoritativa do contrato em Go e fazer o runner consumir somente os valores normalizados necessários.
- [x] Fazer simulação e relatório usarem explicitamente o mesmo perfil selecionado.
- [x] Copiar `profile.json` e gerar `execution-plan.json` no diretório de cada run.
- [x] Fazer cada cenário declarar share, participantes hot/cold, valores, provisionamento e outcome observável esperado.
- [x] Usar nomes de cenário apenas para identidade e agrupamento, sem comportamento escondido por `type` no Go.
- [x] Alocar automaticamente faixas consecutivas de participantes, sem `firstPair` no perfil.
- [x] Remover seed do contrato e gerar blocos de 100 originais com ordenação determinística pelo índice do bloco.
- [x] Gerar valores variados e distribuição desigual entre pares quentes e frios.
- [x] Provisionar pagadores com saldo fixo ou cobertura dos débitos gerados conforme a política declarada pelo cenário.
- [x] Provisionar os pagadores de insufficient-funds deterministicamente com saldo zero.
- [x] Preservar `uniform-smoke` como baseline compatível com a carga uniforme anterior.

## Fatia 1 — Resultado observável de saldo insuficiente (concluída)

**Resultado:** `mixed-outcomes-smoke` prova pelo fluxo externo que happy-path retorna HTTP `2xx` e notifica `ACSC`, enquanto insufficient-funds retorna HTTP `2xx` e notifica `RJCT / AM04`.

### Semântica interna na SPI

- [x] Persistir falta de saldo como `REJECTED / INSUFFICIENT_FUNDS`, em vez de `ACCEPTED_IN_PROCESS`.
- [x] Auditar a transição para rejeição com status e motivo estáveis.
- [x] Provar em testes da SPI que a rejeição não cria settlement nem altera os fundos.
- [x] Manter esses invariantes nos testes do serviço responsável, sem duplicá-los por consultas PostgreSQL no load-test.

### Outcome observável no load-test

- [x] Declarar por cenário `deliverySemantics: at-least-once`, status `pacs.002` e reason codes esperados.
- [x] Capturar `TxSts` e `StsRsnInf[].Rsn.Cd` das notificações entregues ao pagador.
- [x] Preservar status e reasons em `events.csv` para reprodução offline do relatório.
- [x] Aceitar uma ou mais entregas compatíveis e usar a primeira para latência.
- [x] Tratar ausência, status divergente ou reasons divergentes como violação por cenário.
- [x] Validar correção para todos os originais do run, incluindo warmup, mantendo métricas de performance somente na janela ativa.
- [x] Registrar um run funcional curto com zero violações observáveis.

### Critério de saída

`profile.json`, `execution-plan.json`, `starts.csv` e `events.csv` reproduzem o relatório sem acesso ao banco, e o run curto detecta ausência ou outcome divergente sem penalizar entregas repetidas compatíveis.

## Fatia 2A — Replay idêntico de pacs.008 (concluída)

**Resultado:** `mixed-outcomes-smoke` mantém a taxa de pagamentos originais e acrescenta retransmissões idênticas de `pacs.008` pelo ingresso normal.

O modelo exercitado é: em `10%` das submissões, o PSP não obtém uma resposta conclusiva e envia uma única repetição idêntica `10s` após o início da tentativa original. `share` e `delay` são configuráveis no perfil; os valores concretos de `mixed-outcomes-smoke` são `0.10` e `10s`.

- [x] Adicionar ao contrato opcional `replay.pacs008.share` e `replay.pacs008.delay`, mantendo `uniform-smoke` sem replay.
- [x] Exigir que `share × 100` produza uma quantidade inteira e selecionar exatamente essa quantidade em cada bloco completo de 100 originais.
- [x] Tornar a seleção determinística pelo índice do bloco e independente da ordenação dos cenários de negócio.
- [x] Construir o payload uma vez, não modificá-lo e enviar bodies byte a byte iguais no original e no replay.
- [x] Agendar o replay antes de conhecer a resposta original, para `requestStartedAt + delay`, inclusive quando o original ainda está em andamento ou termina com status `0`, `4xx` ou `5xx`.
- [x] Usar um scheduler compartilhado e workers limitados, sem criar uma goroutine por pagamento agendado.
- [x] Reenviar pelo `/transfer` normal com o mesmo pagador e as mesmas credenciais mTLS.
- [x] Registrar a seleção em `starts.csv`, as tentativas em `replays.csv` e os caminhos dos artefatos em `run-window.json`.
- [x] Manter `targetTxRate` como taxa de originais e expor separadamente `original_payments_started`, `pacs008_replays_started` e `total_ingress_started`.
- [x] Validar no run inteiro replay ausente, excedente, não selecionado, desconhecido, metadados divergentes, HTTP não `2xx` e início anterior ao delay.
- [x] Manter o JSON público de replay compacto: `attempted`, `accepted` e `violations`; a taxonomia detalhada permanece interna aos testes do load-tool.
- [x] Garantir igualdade integral dos bodies nos testes do gerador, sem tentar inferi-la a partir do CSV ou de identidade de referência do `[]byte`.
- [x] Registrar um run funcional curto com 126 replays aceitos e zero violações.

### Critério de saída

O perfil, o plano resolvido, `starts.csv`, `replays.csv` e `events.csv` reproduzem o relatório; testes provam seleção exata por bloco, agendamento independente da resposta e igualdade dos bodies; um run curto prova o fluxo externo com zero violações.

## Fatia 2B — Repetição idêntica de pacs.002 (ativa)

**Resultado:** um workload normal acrescenta repetições idênticas de status pelo ingresso usado pelo PSP recebedor, sem reduzir a taxa de pagamentos originais nem produzir outcomes contraditórios para o pagador.

- [ ] Refinar antes da implementação o modelo de repetição de `pacs.002`: mensagens elegíveis, proporção, referência temporal e atraso que representam a retransmissão do PSP recebedor.
- [ ] Estender o contrato somente com os parâmetros necessários ao modelo aprovado, sem introduzir um cenário de negócio ou workload autônomo de duplicidade.
- [ ] Selecionar repetições deterministicamente sem alterar a distribuição de happy-path e insufficient-funds.
- [ ] Construir cada `pacs.002` selecionado uma vez e reenviar conteúdo idêntico, com o mesmo identificador e PSP recebedor.
- [ ] Enviar original e repetição pelo ingresso normal `/transfer/status` com mTLS; não publicar diretamente no Kafka nem manipular offsets.
- [ ] Manter `targetTxRate` como taxa de pagamentos originais e contabilizar `pacs.002` repetidos como carga adicional.
- [ ] Registrar tentativas repetidas em artefato reproduzível e expor separadamente contagem, taxa, aceitação e violações de `pacs.002`.
- [ ] Validar pelo fluxo externo que o pagador continua recebendo apenas outcomes compatíveis com seu cenário sob semântica `at-least-once`.
- [ ] Cobrir nos testes focados da SPI que repetição idêntica não duplica settlement, débito, crédito, auditoria ou obrigação de notificação; não consultar o banco pelo load-tool.
- [ ] Cobrir separadamente, em testes funcionais negativos focados, status divergente para o mesmo pagamento; não misturá-lo ao workload de performance.
- [ ] Registrar um run funcional curto com repetição `pacs.002` habilitada e zero violações.

### Critério de saída

O modelo de repetição está explícito no contrato e na documentação; simulador e relatório reproduzem e validam as tentativas pelos artefatos do run; testes focados preservam a idempotência interna; e um run curto prova outcomes externos compatíveis sem acesso direto ao banco.

## Fatia 3 — Matriz final e handoff para estabilização

**Resultado:** a task de estabilização recebe uma matriz funcional validada e sabe quais perfis ou workloads usar em cada experimento de performance.

- [ ] Consolidar o baseline happy-path e o workload de resultados mistos, ambos com a distribuição hot-pair existente e com as repetições aplicáveis.
- [ ] Refinar antes do handoff as proporções de outcomes e repetições; `80/20` e os valores atuais de replay são parâmetros funcionais, não representatividade presumida de produção.
- [ ] Registrar para cada perfil ou workload: comando, objetivo, distribuição, taxa de originais, carga adicional, outcomes esperados e evidência do run funcional curto.
- [ ] Identificar quais perfis ou workloads a estabilização deve exercitar e qual aspecto do sistema cada um cobre.
- [ ] Entregar a meta de `2.000` pagamentos originais/s para os workloads selecionados, com mensagens repetidas como carga adicional mensurada separadamente.
- [ ] Entregar a matriz à task [`operacao-testes.md`](../../Backlog/operacao-testes.md), sem concluir capacidade a partir dos runs funcionais curtos.

## Critérios de conclusão da task guarda-chuva

- os perfis são selecionáveis sem editar uma configuração principal;
- cada cenário declara provisionamento e outcome externamente observável, sem comportamento implícito por nome ou `type`;
- happy-path e insufficient-funds são diferenciados automaticamente sem classificar rejeição esperada como perda técnica;
- a distribuição hot-pair existente representa o tráfego concentrado do MVP;
- `targetTxRate` representa pagamentos originais, enquanto `pacs.008` e `pacs.002` repetidos são carga adicional com métricas separadas;
- repetições idênticas passam somente pelo ingresso normal e têm correção externa validada no run inteiro;
- invariantes persistidos permanecem nos testes dos serviços responsáveis, sem acoplamento do load-tool ao PostgreSQL;
- cada perfil ou workload entregue possui pelo menos um run funcional curto registrado;
- o handoff identifica quais workloads a estabilização deve executar, por quê e como avaliar `2.000` originais/s além da carga configurada de repetição;
- qualquer falha funcional encontrada gera correção ou task focada antes da estabilização.

## Fora de escopo

- fazer tuning de consumers, producers, pools, banco, batches ou recursos;
- definir budgets finais de CPU e memória ou thresholds definitivos de p95, p99 e throughput;
- executar runs longos de capacidade, a validação oficial repetida de 15 minutos ou gates finais de performance;
- validar duas stacks compartilhando o mesmo PostgreSQL;
- consultar PostgreSQL no load-test para validar status, settlement, saldos, auditoria ou outbox;
- exportar `payment-outcomes.csv` ou `payment-audit-events.csv` no MVP;
- gerar duplicatas divergentes dentro dos workloads de performance; conflitos de mesmo identificador com conteúdo diferente permanecem em testes funcionais negativos focados;
- classificar múltiplos frames por `communication_id`, obrigação lógica ou redelivery operacional;
- adicionar hot sender/fan-out, hot receiver/fan-in ou hot partition Kafka; hot-pair é suficiente para o MVP, e topologias direcionais só serão adicionadas se a estabilização revelar necessidade concreta;
- publicar records diretamente em tópicos Kafka, manipular offsets ou provocar redelivery no broker;
- injetar falhas deliberadas de componente ou rede, incluindo PSP offline/reconnect, restart do gateway, ACK perdido, retry e redelivery operacional; esses cenários pertencem à task [`Engenharia de caos e resiliência operacional`](../Backlog/engenharia-caos-resiliencia-operacional.md);
- implementar retentativa automática de pagamentos em `ACCEPTED_IN_PROCESS`.
