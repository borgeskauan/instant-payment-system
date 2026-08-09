# Matriz funcional de workloads para estabilização de performance

- [ ] Preparar a matriz funcional de workloads para estabilização de performance

## Objetivo

Preparar e validar funcionalmente a matriz de workloads que será usada depois pela task **Estabilizar teste de carga dentro do budget de CPU**, em [`operacao-testes.md`](../../Backlog/operacao-testes.md).

Essa matriz cobre caminho feliz, resultados de negócio mistos, tráfego hot-pair, fases temporais de carga, duplicidade e replay de pagamentos e status. Cada workload deve ser reproduzível e possuir um run funcional curto que prove seus resultados e invariantes antes de ser usado em experimentos de performance.

Esta é uma task guarda-chuva. O trabalho deve avançar uma fatia por vez; somente a fatia marcada como ativa é o próximo passo. A correção semântica de saldo insuficiente no SPI permanece na fatia ativa para que o workload misto tenha um resultado de negócio verificável, embora a mudança pertença tecnicamente ao SPI.

Esta task não faz tuning, runs longos de performance nem define gates finais de CPU, memória, latência ou throughput. Essas decisões pertencem à estabilização, depois que a matriz funcional estiver pronta.

## Estado atual

- `uniform-smoke` preserva a carga uniforme anterior no contrato novo;
- `mixed-outcomes-smoke` executa caminho feliz e saldo insuficiente no mesmo run, com participantes isolados e provisionamento derivado;
- o relatório separa aceitação HTTP, confirmação esperada ou ausente, latência, SLA e violações por cenário;
- o run funcional curto de 2026-08-08 terminou com 1.251 requisições aceitas, confirmações completas no caminho feliz, ausência de confirmação para saldo insuficiente e zero violações;
- falta validar o resultado de negócio persistido: hoje a SPI grava falta de liquidez como `ACCEPTED_IN_PROCESS`, sem distingui-la de outros processamentos pendentes.

## Fatia 0 — Contrato e execução reproduzível (concluída)

**Resultado:** perfis selecionáveis descrevem cenários e produzem um plano de execução resolvido, sem caminhos arbitrários, seed ou alocação manual de participantes.

- [x] Adicionar perfis de teste no load-tool, com múltiplos arquivos de configuração por cenário.
- [x] Permitir selecionar o perfil no script de execução.
- [x] Fazer simulação e relatório usarem exatamente o mesmo perfil selecionado.
- [x] Copiar o perfil e o plano de execução resolvido para o diretório de resultados de cada run.
- [x] Fazer cada perfil definir carga, distribuição de participantes, valores e resultados de negócio esperados, derivando o provisionamento no Go.
- [x] Preservar `uniform-smoke` como comparação compatível com a carga uniforme anterior.
- [x] Alocar automaticamente faixas consecutivas de participantes, sem expor `firstPair` no perfil.
- [x] Remover seed do contrato e gerar blocos embaralhados de 100 transações de forma determinística pelo índice do bloco.
- [x] Registrar em `execution-plan.json` as faixas de participantes e o provisionamento efetivamente usados.
- [x] Gerar valores de transação variados.
- [x] Simular distribuição desigual entre poucos participantes quentes e muitos participantes frios.
- [x] Provisionar pagadores do cenário de saldo insuficiente deterministicamente com saldo zero.
- [x] Medir saldo insuficiente separadamente do caminho que deve receber confirmação final.
- [x] Calcular a taxa de confirmação somente sobre cenários cujo resultado esperado exige confirmação.

## Fatia 1 — Resultado persistido de saldo insuficiente (ativa)

**Resultado:** `mixed-outcomes-smoke` prova, usando artefatos autocontidos do run, que pagamentos felizes liquidam uma vez e pagamentos sem saldo são rejeitados pelo motivo correto, sem liquidação nem movimentação de fundos.

### Semântica e auditoria na SPI

- [ ] Persistir falta de saldo como status `REJECTED` com motivo `INSUFFICIENT_FUNDS`, em vez de `ACCEPTED_IN_PROCESS`.
- [ ] Auditar a transição para rejeição com status e motivo estáveis.
- [ ] Provar que a rejeição não cria `SETTLEMENT_APPLIED` nem altera os fundos.

### Evidências produzidas pela orquestração do load-test

- [ ] Fazer o runner chamar, depois do drain, um exportador pertencente ao load-test.
- [ ] Exportar somente os `EndToEndId` iniciados pelo run atual, inclusive quando for usado `--no-reset-state`.
- [ ] Gravar no diretório do run `payment-outcomes.csv`, com o resultado final normalizado de cada pagamento.
- [ ] Gravar no diretório do run `payment-audit-events.csv`, com as evidências de auditoria correspondentes.
- [ ] Manter o relatório independente do banco vivo: ele deve consumir os artefatos exportados, não consultar PostgreSQL diretamente.

### Correlação e validação no relatório

- [ ] Cruzar o resultado persistido de cada `EndToEndId` com o `scenario_type` registrado pelo simulador.
- [ ] Contabilizar violações por cenário sem misturar rejeição esperada com perda técnica.
- [ ] Validar que `happy-path` termina liquidado exatamente uma vez.
- [ ] Validar que `insufficient-funds` termina em `REJECTED` com motivo `INSUFFICIENT_FUNDS`, sem settlement.
- [ ] Registrar um run funcional curto de `mixed-outcomes-smoke` com zero violações persistidas.

### Critério de saída da fatia

A fatia termina quando o diretório de um run contém dados suficientes para reproduzir o relatório sem acesso ao banco e o relatório detecta automaticamente status, motivo, liquidação ausente ou duplicada e divergências por cenário.

## Fatia 2 — Formato temporal da carga

**Resultado:** um perfil pode representar transições de carga, não apenas TPS uniforme.

- [ ] Modelar ramp-up, pico, carga sustentada, queda e período ocioso.
- [ ] Fazer simulador, janela do run e relatório usarem a mesma curva resolvida.
- [ ] Criar um run funcional curto que percorra todas as fases.

## Fatia 3 — Duplicidade e replay pelo ingresso normal

**Resultado:** pagamentos e status repetidos atravessam o mesmo ingresso normal das mensagens originais e não duplicam liquidação nem corrompem status, saldo, auditoria, outbox ou confirmação.

- [ ] Reenviar um `pacs.008` idêntico, com o mesmo `EndToEndId` e conteúdo, pela interface normal de ingresso usada pelo PSP.
- [ ] Enviar duplicatas divergentes de `pacs.008` ou `pacs.002`/status, nos casos em que o contrato as distingue, e validar rejeição explícita sem efeitos de negócio adicionais.
- [ ] Reenviar `pacs.002`/status pela interface normal de ingresso, incluindo mensagens repetidas para pagamentos já liquidados ou confirmados.
- [ ] Fazer todo replay funcional passar pelas APIs e protocolos normais do sistema; o load-tool não publica records diretamente no Kafka nem manipula offsets de consumers.
- [ ] Validar `notSettledPaymentIds` e atualizações de status com IDs duplicados.
- [ ] Expor nos resultados contagens de duplicidade e replay.
- [ ] Validar automaticamente ausência de dupla liquidação, alteração indevida de saldo e confirmação inconsistente.

## Fatia 4 — Matriz final e handoff para estabilização

**Resultado:** a task de estabilização recebe uma matriz funcional validada e sabe quais perfis e workloads usar em cada experimento de performance.

- [ ] Consolidar os workloads funcionalmente validados de caminho feliz, resultados mistos, tráfego hot-pair, fases temporais, duplicidade e replay.
- [ ] Registrar para cada workload o perfil ou comando, o objetivo, a distribuição de tráfego, os resultados esperados e as evidências do run funcional curto.
- [ ] Identificar explicitamente quais perfis e workloads a task de estabilização deve usar e o aspecto do sistema que cada um exercita.
- [ ] Entregar a matriz à task [`operacao-testes.md`](../../Backlog/operacao-testes.md), sem tirar conclusões de capacidade a partir dos runs funcionais curtos.

## Critérios de conclusão da task guarda-chuva

- os perfis são selecionáveis sem editar uma configuração principal;
- cada perfil ou workload declara os resultados de negócio e invariantes aplicáveis, verificados contra estado persistido e auditoria;
- rejeição esperada não é classificada como perda técnica;
- a distribuição hot-pair existente representa o tráfego concentrado do MVP;
- um workload funcional percorre ramp-up, pico, carga sustentada, queda e período ocioso;
- duplicidade e replay possuem invariantes automáticas de saldo, auditoria, outbox e confirmação;
- cada perfil ou workload entregue possui pelo menos um run funcional curto registrado;
- o handoff identifica quais perfis e workloads serão usados pela task de estabilização e por quê;
- falhas funcionais encontradas geram correção ou task focada.

## Fora de escopo

- definir o budget final de CPU e memória por serviço;
- fechar thresholds definitivos de p95, p99 e throughput;
- fazer qualquer tuning de consumers, producers, pools, banco ou recursos; evidências encontradas nesta task devem ser entregues à task de estabilização;
- executar a validação oficial repetida de 15 minutos;
- validar duas stacks compartilhando o mesmo PostgreSQL;
- adicionar topologias independentes de participantes — hot sender/fan-out, hot receiver/fan-in ou hot partition Kafka; a distribuição hot-pair existente é suficiente para o MVP, e esses cenários ficam para trabalho futuro de performance e diagnóstico, somente se a estabilização revelar uma necessidade concreta de isolar contenção no pagador ou recebedor;
- publicar records diretamente em tópicos Kafka, manipular offsets de consumers ou provocar redelivery no broker; redelivery operacional pertence à task [`Engenharia de caos e resiliência operacional`](../Backlog/engenharia-caos-resiliencia-operacional.md);
- injetar falhas deliberadas de componente ou rede, incluindo PSP offline e reconexão, restart do `notification-gateway`, ACK perdido, retry e redelivery; esses cenários pertencem à task [`Engenharia de caos e resiliência operacional`](../Backlog/engenharia-caos-resiliencia-operacional.md);
- implementar retentativa automática de pagamentos em `ACCEPTED_IN_PROCESS`.
