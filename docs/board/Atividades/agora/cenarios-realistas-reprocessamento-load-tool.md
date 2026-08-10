# Matriz funcional de workloads para estabilização de performance

- [ ] Preparar a matriz funcional de workloads para estabilização de performance

## Objetivo

Preparar e validar funcionalmente a matriz de workloads que será usada depois pela task **Estabilizar teste de carga dentro do budget de CPU**, em [`operacao-testes.md`](../../Backlog/operacao-testes.md).

Essa matriz cobre caminho feliz, resultados de negócio mistos, tráfego hot-pair, fases temporais de carga, duplicidade e replay de pagamentos e status. Cada workload deve ser reproduzível e possuir um run funcional curto que prove seus resultados e invariantes antes de ser usado em experimentos de performance.

Esta é uma task guarda-chuva. O trabalho deve avançar uma fatia por vez; somente a fatia marcada como ativa é o próximo passo. A correção semântica de saldo insuficiente no SPI permanece na fatia ativa para que o workload misto tenha um resultado de negócio verificável, embora a mudança pertença tecnicamente ao SPI.

Esta task não faz tuning, runs longos de performance nem define gates finais de CPU, memória, latência ou throughput. Essas decisões pertencem à estabilização, depois que a matriz funcional estiver pronta.

## Estado atual

- `uniform-smoke` preserva a carga uniforme anterior no contrato novo;
- `mixed-outcomes-smoke` executa caminho feliz e saldo insuficiente no mesmo run, com participantes isolados e políticas explícitas de provisionamento;
- nomes de cenário identificam e agrupam resultados, sem selecionar comportamento implícito no Go;
- o relatório separa correção observável no run inteiro de throughput, latência e SLA na janela ativa;
- notificações `pacs.002` seguem semântica `at-least-once`: repetições com o mesmo outcome são válidas e outcomes contraditórios são violações;
- o run funcional curto de 2026-08-09 (`observable-pacs002-outcomes-smoke/20260809_233329`) terminou com 1.251 requisições aceitas e notificadas ao pagador, zero violações, `ACSC` nos 1.001 pagamentos felizes e `RJCT`/`AM04` nos 250 pagamentos sem saldo;
- a SPI já grava falta de liquidez como `REJECTED` com motivo `INSUFFICIENT_FUNDS`; persistência, settlement, saldos, auditoria, outbox e atomicidade permanecem cobertos pelos testes do SPI, não pelo load-test MVP.

## Fatia 0 — Contrato e execução reproduzível (concluída)

**Resultado:** perfis selecionáveis descrevem cenários e produzem um plano de execução resolvido, sem caminhos arbitrários, seed ou alocação manual de participantes.

- [x] Adicionar perfis de teste no load-tool, com múltiplos arquivos de configuração por cenário.
- [x] Permitir selecionar o perfil no script de execução.
- [x] Fazer simulação e relatório usarem exatamente o mesmo perfil selecionado.
- [x] Copiar o perfil e o plano de execução resolvido para o diretório de resultados de cada run.
- [x] Fazer cada perfil definir carga, distribuição de participantes, valores, política de provisionamento e resultados observáveis esperados, resolvendo no Go apenas os valores concretos de execução.
- [x] Preservar `uniform-smoke` como comparação compatível com a carga uniforme anterior.
- [x] Alocar automaticamente faixas consecutivas de participantes, sem expor `firstPair` no perfil.
- [x] Remover seed do contrato e gerar blocos embaralhados de 100 transações de forma determinística pelo índice do bloco.
- [x] Registrar em `execution-plan.json` as faixas de participantes e o provisionamento efetivamente usados.
- [x] Gerar valores de transação variados.
- [x] Simular distribuição desigual entre poucos participantes quentes e muitos participantes frios.
- [x] Provisionar pagadores com saldo fixo ou cobertura dos débitos gerados conforme a política declarada por cenário.
- [x] Provisionar pagadores do cenário de saldo insuficiente deterministicamente com saldo zero.
- [x] Medir a contagem e a latência de notificações ao pagador separadamente por cenário, sem tratar toda `pacs.002` como confirmação positiva.

## Fatia 1 — Resultado observável de saldo insuficiente (concluída)

**Resultado:** `mixed-outcomes-smoke` prova pelo fluxo externo normal que pagamentos felizes retornam HTTP `2xx` e notificam `ACSC`, enquanto pagamentos sem saldo retornam HTTP `2xx` e notificam `RJCT` com reason `AM04`.

### Semântica interna na SPI

- [x] Persistir falta de saldo como status `REJECTED` com motivo `INSUFFICIENT_FUNDS`, em vez de `ACCEPTED_IN_PROCESS`.
- [x] Auditar a transição para rejeição com status e motivo estáveis.
- [x] Provar que a rejeição não cria `SETTLEMENT_APPLIED` nem altera os fundos.
- [x] Manter esses invariantes em testes do SPI, sem duplicá-los por consultas PostgreSQL no load-test.

### Outcome observável no load-test

- [x] Declarar em cada cenário `deliverySemantics: at-least-once`, status PACS.002 e reason codes esperados.
- [x] Capturar `TxSts` e `StsRsnInf[].Rsn.Cd` das notificações entregues ao pagador.
- [x] Preservar os códigos observados em `events.csv` para reprodução offline do relatório.
- [x] Aceitar uma ou mais entregas idênticas do outcome esperado, sem tratá-las como excesso.
- [x] Tratar ausência de notificação, status divergente ou reason divergente como violação por cenário.
- [x] Não tentar decidir nesta fatia se múltiplos frames representam obrigações lógicas distintas ou redelivery.

### Escopo da validação e métricas

- [x] Validar HTTP e outcome PACS.002 para todos os pagamentos do run, incluindo warmup.
- [x] Manter throughput, latência e SLA limitados aos pagamentos iniciados na janela ativa.
- [x] Contabilizar cada pagamento compatível uma vez nas métricas e usar sua primeira entrega compatível para latência.
- [x] Validar `happy-path` como HTTP `2xx` mais PACS.002 `ACSC` sem reasons.
- [x] Validar `insufficient-funds` como HTTP `2xx` mais PACS.002 `RJCT` com `AM04`.
- [x] Registrar um run funcional curto de `mixed-outcomes-smoke` com zero violações observáveis.

### Critério de saída da fatia

A fatia termina quando `profile.json`, `execution-plan.json`, `starts.csv` e `events.csv` bastam para reproduzir o relatório sem acesso ao banco, e um run curto detecta automaticamente ausência, status divergente e reason divergente sem penalizar entregas repetidas compatíveis.

## Fatia 2 — Formato temporal da carga (ativa)

**Resultado:** um perfil pode representar transições de carga, não apenas TPS uniforme.

- [ ] Modelar ramp-up, pico, carga sustentada, queda e período ocioso.
- [ ] Fazer simulador, janela do run e relatório usarem a mesma curva resolvida.
- [ ] Criar um run funcional curto que percorra todas as fases.

## Fatia 3 — Duplicidade e replay pelo ingresso normal

**Resultado:** pagamentos e status repetidos atravessam o mesmo ingresso normal das mensagens originais e preservam o outcome externo esperado; invariantes internos de liquidação, saldo, auditoria e outbox permanecem nos testes do SPI.

- [ ] Reenviar um `pacs.008` idêntico, com o mesmo `EndToEndId` e conteúdo, pela interface normal de ingresso usada pelo PSP.
- [ ] Enviar duplicatas divergentes de `pacs.008` ou `pacs.002`/status, nos casos em que o contrato as distingue, e validar rejeição explícita sem efeitos de negócio adicionais.
- [ ] Reenviar `pacs.002`/status pela interface normal de ingresso, incluindo mensagens repetidas para pagamentos já liquidados, rejeitados ou notificados.
- [ ] Fazer todo replay funcional passar pelas APIs e protocolos normais do sistema; o load-tool não publica records diretamente no Kafka nem manipula offsets de consumers.
- [ ] Validar `notSettledPaymentIds` e atualizações de status com IDs duplicados.
- [ ] Expor nos resultados contagens de duplicidade e replay.
- [ ] Validar automaticamente ausência de outcomes externos contraditórios e manter os invariantes persistidos correspondentes nos testes focados do SPI.

## Fatia 4 — Matriz final e handoff para estabilização

**Resultado:** a task de estabilização recebe uma matriz funcional validada e sabe quais perfis e workloads usar em cada experimento de performance.

- [ ] Consolidar os workloads funcionalmente validados de caminho feliz, resultados mistos, tráfego hot-pair, fases temporais, duplicidade e replay.
- [ ] Registrar para cada workload o perfil ou comando, o objetivo, a distribuição de tráfego, os resultados esperados e as evidências do run funcional curto.
- [ ] Identificar explicitamente quais perfis e workloads a task de estabilização deve usar e o aspecto do sistema que cada um exercita.
- [ ] Entregar a matriz à task [`operacao-testes.md`](../../Backlog/operacao-testes.md), sem tirar conclusões de capacidade a partir dos runs funcionais curtos.

## Critérios de conclusão da task guarda-chuva

- os perfis são selecionáveis sem editar uma configuração principal;
- cada perfil ou workload declara os resultados de negócio externamente observáveis; invariantes persistidos permanecem nos testes dos serviços responsáveis;
- rejeição esperada não é classificada como perda técnica;
- a distribuição hot-pair existente representa o tráfego concentrado do MVP;
- um workload funcional percorre ramp-up, pico, carga sustentada, queda e período ocioso;
- duplicidade e replay possuem validação externa automática no workload e testes internos focados de saldo, auditoria e outbox no SPI;
- cada perfil ou workload entregue possui pelo menos um run funcional curto registrado;
- o handoff identifica quais perfis e workloads serão usados pela task de estabilização e por quê;
- falhas funcionais encontradas geram correção ou task focada.

## Fora de escopo

- definir o budget final de CPU e memória por serviço;
- fechar thresholds definitivos de p95, p99 e throughput;
- fazer qualquer tuning de consumers, producers, pools, banco ou recursos; evidências encontradas nesta task devem ser entregues à task de estabilização;
- executar a validação oficial repetida de 15 minutos;
- validar duas stacks compartilhando o mesmo PostgreSQL;
- consultar PostgreSQL no load-test para validar status, settlement, saldos, auditoria ou outbox;
- exportar `payment-outcomes.csv` ou `payment-audit-events.csv` no MVP;
- classificar múltiplos frames de notificação por `communication_id`, obrigação lógica ou redelivery; essa distinção pertence à engenharia de confiabilidade;
- adicionar topologias independentes de participantes — hot sender/fan-out, hot receiver/fan-in ou hot partition Kafka; a distribuição hot-pair existente é suficiente para o MVP, e esses cenários ficam para trabalho futuro de performance e diagnóstico, somente se a estabilização revelar uma necessidade concreta de isolar contenção no pagador ou recebedor;
- publicar records diretamente em tópicos Kafka, manipular offsets de consumers ou provocar redelivery no broker; redelivery operacional pertence à task [`Engenharia de caos e resiliência operacional`](../Backlog/engenharia-caos-resiliencia-operacional.md);
- injetar falhas deliberadas de componente ou rede, incluindo PSP offline e reconexão, restart do `notification-gateway`, ACK perdido, retry e redelivery; esses cenários pertencem à task [`Engenharia de caos e resiliência operacional`](../Backlog/engenharia-caos-resiliencia-operacional.md);
- implementar retentativa automática de pagamentos em `ACCEPTED_IN_PROCESS`.
