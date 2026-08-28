# Matriz funcional de workloads para estabilização de performance

- [x] Preparar a matriz funcional de workloads para estabilização de performance

## Objetivo

Preparar e validar funcionalmente os workloads que serão usados depois pela task **Estabilizar teste de carga dentro do budget de CPU**, em [`estabilizar-teste-carga-budget-cpu.md`](estabilizar-teste-carga-budget-cpu.md).

A matriz cobre caminho feliz, resultados de negócio mistos, tráfego hot-pair e repetições idênticas de `pacs.008` e `pacs.002` aplicadas dentro dos workloads normais. Repetição é uma dimensão do workload, não um cenário de negócio nem um workload independente.

Para o handoff de performance, a meta contratual de `2.000 TPS` significa `2.000` pagamentos originais iniciados por segundo. Replays de pagamentos e repetições de status são carga adicional: não substituem originais para manter a taxa nominal e devem ser medidos separadamente do ingresso total.

Esta é uma task guarda-chuva e avança uma fatia por vez. Ela prepara workloads funcionalmente corretos e reproduzíveis, mas não faz tuning, runs longos de performance nem define gates finais de CPU, memória, latência ou throughput. Evidências de capacidade ou degradação encontradas aqui devem ser entregues à task de estabilização.

## Estado atual

- os perfis selecionáveis ficam no catálogo canônico `load-test/profiles/`, separado da implementação interna em Go;
- `uniform-smoke` é o baseline sem replay: caminho feliz, `2.000` pagamentos originais/s, `1m` de warmup, `1m` de janela ativa, `30s` de drain, 10 pares quentes, 40 frios e `80%` do tráfego nos pares quentes;
- `mixed-outcomes-smoke` é o workload funcional curto: 100 pagamentos originais/s, `5s` de warmup, `10s` de janela ativa e `10s` de drain;
- `mixed-outcomes-2k-15m` reproduz o mesmo workload funcional com 2.000 pagamentos originais/s, `1m` de warmup, `15m` de janela ativa e `30s` de drain; ele é o único perfil longo entregue à estabilização;
- `mixed-outcomes-smoke` distribui deterministicamente cada bloco completo de 100 originais em `80%` happy-path e `20%` insufficient-funds, com participantes isolados e provisionamento declarado por cenário;
- a divisão `80/20` é funcional, não uma estimativa final de produção, e foi mantida como mix explícito para a primeira estabilização;
- happy-path espera HTTP `2xx` e notificação ao pagador `pacs.002 ACSC`; insufficient-funds espera HTTP `2xx` e notificação `pacs.002 RJCT` com reason `AM04`;
- notificações ao pagador usam semântica `at-least-once`: uma ou mais entregas compatíveis são válidas, ausência ou outcome contraditório é violação;
- `targetTxRate` controla somente pagamentos originais; replays são agendados como carga adicional;
- os dois perfis mixed-outcomes selecionam `5%` dos originais para uma única retransmissão `pacs.008` idêntica, iniciada `10s` após o começo da tentativa original e independentemente de sua resposta HTTP;
- para cada bloco completo de 100 `pacs.002` originais efetivamente iniciados, os dois perfis mixed-outcomes selecionam 5 para uma única repetição idêntica, iniciada `10s` após o começo do status original e independentemente de sua resposta HTTP;
- as seleções de `pacs.008` e `pacs.002` usam populações, configurações e contagens próprias; notificações `pacs.008` repetidas no PSP recebedor são deduplicadas e não criam novos status originais;
- o replay `pacs.008` já passa pelo ingresso normal `/transfer`, com o mesmo pagador e mTLS, sem publicação direta no Kafka;
- a repetição de `pacs.002` passa pelo ingresso normal `/transfer/status`, com o mesmo PSP recebedor, mTLS e body byte a byte idêntico;
- `events/pacs008-starts.csv` registra a seleção de `pacs.008`, `events/pacs002-starts.csv` registra os status originais e a seleção de `pacs.002`, `events/notifications.csv` registra os outcomes observados e `events/replays.csv` registra as tentativas repetidas dos dois tipos;
- o simulador calcula e persiste antes da geração `generation_started_at`, `active_started_at`, `generation_ended_at` e `replay_deadline_at`; o relatório consome esses instantes sem derivar a janela dos CSVs;
- warmup e janela ativa são intervalos semiabertos, nenhum original pode começar em `generation_ended_at` ou depois, e backlog não prolonga o deadline fixo do experimento;
- `sla-report.json` é centrado nos cenários: cada cenário reúne seu tráfego de pagamentos e `pacs.002` originais, seu outcome lógico e suas métricas, enquanto geração de originais e replays permanecem globais;
- no relatório, `started` significa tentativa HTTP iniciada e `accepted` significa resposta HTTP `2xx`; aceitação HTTP permanece distinta do outcome assíncrono de negócio;
- o relatório valida tráfego e outcomes de negócio no run inteiro; para replays,
  qualifica somente a quantidade agregada selecionada/iniciada e a aceitação
  HTTP, enquanto identidade, timing e igualdade do payload permanecem nos
  testes do gerador; throughput, latência e threshold permanecem restritos à
  janela ativa;
- o runner público executa uma única chamada `go-loadtool run --run-dir`, coleta diagnósticos mesmo quando essa chamada falha e preserva o exit code original do Go;
- `loadtool_finished_at` registra o fim da chamada única, enquanto `replay_deadline_at` permanece o fim autoritativo da janela experimental;
- o relatório publica `valid` como decisão agregada, verdadeira somente quando geração, todos os cenários e os dois tipos de replay têm zero violações; o runner retorna zero somente para `valid: true`;
- a implementação da repetição deliberada de `pacs.002` está concluída e validada funcionalmente no load-tool;
- o run de 2026-08-09 (`observable-pacs002-outcomes-smoke/20260809_233329`) terminou com 1.251 originais aceitos e notificados, zero violações, `ACSC` nos 1.001 happy-path e `RJCT`/`AM04` nos 250 insufficient-funds;
- as evidências históricas de 2026-08-11 e o primeiro smoke do bundle usaram o share anterior de `10%`; o run `pacs008-replay-functional-smoke/20260811_020918` manteve 100 originais/s na janela ativa, aceitou os 1.251 originais e os 126 replays selecionados e terminou com zero violações de replay ou outcome; o menor atraso observado foi `10,000038s`;
- o run de 2026-08-11 (`pacs002-replay-functional-smoke/20260811_040749`) iniciou exatamente 1.000 originais na janela ativa (100/s), aceitou 1.250 originais e 1.250 status, executou 126 replays `pacs.008` e 126 replays `pacs.002` e terminou com zero violações de geração, HTTP, replay ou outcome;
- o run de caracterização de 2026-08-11 (`phase-2c1-characterization-smoke/20260811_050401`) confirmou 1.250 originais aceitos, 1.000 originais na janela ativa, 1.000 happy-path, 250 insufficient-funds, 1.250 status e 126 replays aceitos de cada tipo, com zero violações;
- o run de migração de 2026-08-11 (`phase-2c4-single-run-smoke/20260811_142951`) validou o caminho único final com as mesmas contagens caracterizadas, exatamente um `sla-report.json`, 126 replays aceitos de cada tipo e todos os dez campos `violations` em zero;
- o run de 2026-08-12 (`run-bundle-layout-smoke/20260812_000828`) validou o bundle final com 1.250 originais e 1.250 status aceitos, 126 replays aceitos de cada tipo, quatro CSVs em `events/`, snapshots em `inputs/`, logs operacionais em `logs/`, zero violações e nenhum certificado retido;
- o smoke final de 2026-08-12 (`phase-3-workload-matrix-smoke/20260812_012859`) validou a configuração vigente de `5%`: 1.250 originais e 1.250 status aceitos, 1.000 happy-path, 250 insufficient-funds, 64 replays `pacs.008`, 63 replays `pacs.002` e zero violações; as contagens 64/63 incluem o bloco parcial final e caracterizam apenas essa sequência curta;
- a suíte de caracterização protege as populações de originais e status, as contagens separadas dos dois tipos de replay e um único resultado lógico final por pagamento, sem congelar quais `EndToEndId` são selecionados;
- a SPI persiste falta de liquidez como `REJECTED / INSUFFICIENT_FUNDS`; settlement, saldos, auditoria, outbox e atomicidade permanecem cobertos pelos testes focados da SPI, sem consultas PostgreSQL no load-test.
- o runner não trunca mais estado persistente antes dos runs: a preparação automática e obrigatória do ambiente consome `inputs/execution-plan.json`, usa o lag atual dos três consumer groups Kafka como heurística best-effort e somente então provisiona os fundos declarados; `run-load-test.sh` apenas orquestra essa unidade interna, sem alegar quiescência forte ou detectar trabalho residual em outbox/delivery;
- cada resultado usa um bundle fixo: inputs em `inputs/`, registros temporais auditáveis em `events/`, saída textual em `logs/`, diagnósticos opt-in em `diagnostics/`, e somente `run-window.json` e `sla-report.json` na raiz.

## Fatia 0 — Contrato e execução reproduzível (concluída)

**Resultado:** perfis selecionáveis descrevem a execução e produzem um plano resolvido, sem caminhos arbitrários, seed, tipos implícitos ou alocação manual de participantes.

- [x] Selecionar perfis por nome em runner, simulador e relatório, usando `uniform-smoke` por padrão.
- [x] Resolver nomes internamente para `profiles/<name>.json` e rejeitar nomes inválidos, perfis ausentes ou JSON malformado antes de efeitos colaterais do runner.
- [x] Manter a validação semântica autoritativa do contrato em Go e fazer o runner consumir somente os valores normalizados necessários.
- [x] Fazer simulação e relatório usarem explicitamente o mesmo perfil selecionado.
- [x] Copiar `inputs/profile.json` e gerar `inputs/execution-plan.json` no diretório de cada run.
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
- [x] Preservar status e reasons em `events/notifications.csv` como evidência auditável consumida pelo relatório da própria execução.
- [x] Aceitar uma ou mais entregas compatíveis e usar a primeira para latência.
- [x] Tratar ausência, status divergente ou reasons divergentes como violação por cenário.
- [x] Validar correção para todos os originais do run, incluindo warmup, mantendo métricas de performance somente na janela ativa.
- [x] Registrar um run funcional curto com zero violações observáveis.

### Critério de saída

O relatório da própria execução usa os inputs preservados e os eventos auditáveis sem acesso ao banco, e o run curto detecta ausência ou outcome divergente sem penalizar entregas repetidas compatíveis.

## Fatia 2A — Replay idêntico de pacs.008 (concluída)

**Resultado:** `mixed-outcomes-smoke` mantém a taxa de pagamentos originais e acrescenta retransmissões idênticas de `pacs.008` pelo ingresso normal.

O modelo exercitado é: em uma parcela configurável das submissões, o PSP não obtém uma resposta conclusiva e envia uma única repetição idêntica após o delay configurado. Os valores vigentes dos perfis mixed-outcomes são `0.05` e `10s`.

- [x] Adicionar ao contrato opcional `replay.pacs008.share` e `replay.pacs008.delay`, mantendo `uniform-smoke` sem replay.
- [x] Exigir que `share × 100` produza uma quantidade inteira e selecionar exatamente essa quantidade em cada bloco completo de 100 originais.
- [x] Aplicar a seleção à população de pagamentos originais sem alterar a ordenação dos cenários de negócio.
- [x] Construir o payload uma vez, não modificá-lo e enviar bodies byte a byte iguais no original e no replay.
- [x] Agendar o replay antes de conhecer a resposta original, para `requestStartedAt + delay`, inclusive quando o original ainda está em andamento ou termina com status `0`, `4xx` ou `5xx`.
- [x] Usar um scheduler compartilhado e workers limitados, sem criar uma goroutine por pagamento agendado.
- [x] Reenviar pelo `/transfer` normal com o mesmo pagador e as mesmas credenciais mTLS.
- [x] Registrar a seleção em `events/pacs008-starts.csv`, as tentativas em `events/replays.csv` e os caminhos dos artefatos em `run-window.json`.
- [x] Manter `targetTxRate` como taxa de originais e expor separadamente `original_payments_started` e `pacs008_replays_started`.
- [x] Validar no run inteiro replay ausente, excedente, não selecionado, desconhecido, metadados divergentes, HTTP não `2xx` e início anterior ao delay.
- [x] Manter o JSON público de replay compacto: `attempted`, `accepted` e `violations`; a taxonomia detalhada permanece interna aos testes do load-tool.
- [x] Garantir igualdade integral dos bodies nos testes do gerador, sem tentar inferi-la a partir do CSV ou de identidade de referência do `[]byte`.
- [x] Registrar um run funcional curto com 126 replays aceitos e zero violações.

### Critério de saída

O perfil, o plano resolvido e os CSVs auditáveis alimentam o relatório da própria execução; testes provam seleção exata por bloco, agendamento independente da resposta e igualdade dos bodies; um run curto prova o fluxo externo com zero violações.

## Fatia 2B — Repetição idêntica de pacs.002 (concluída)

**Resultado:** um workload normal acrescenta repetições idênticas de status pelo ingresso usado pelo PSP recebedor, sem reduzir a taxa de pagamentos originais nem produzir outcomes contraditórios para o pagador.

O modelo exercitado é: em uma parcela configurável dos `pacs.002` originais, o PSP não obtém uma resposta conclusiva e envia uma única repetição idêntica após o delay configurado. `share` e `delay` são próprios; os valores vigentes dos perfis mixed-outcomes são `0.05` e `10s`.

- [x] Aplicar `pacs002.share` à população de status originais efetivamente iniciados e selecionar exatamente `share × 100` em cada bloco completo de 100 status.
- [x] Aplicar a seleção à população própria de `pacs.002` originais, separada de `pacs.008` e sem alterar a distribuição happy-path/insufficient-funds.
- [x] Deduplicar notificações repetidas de `pacs.008` no PSP recebedor antes de criar o status original.
- [x] Estender o contrato somente com `replay.pacs002.share` e `replay.pacs002.delay`, sem criar cenário de negócio ou workload autônomo de duplicidade.
- [x] Construir cada `pacs.002` selecionado uma vez e reenviar body byte a byte idêntico, com o mesmo identificador e PSP recebedor.
- [x] Agendar a repetição para `statusRequestStartedAt + delay` antes de conhecer a resposta original; timeout, status `0`, `4xx` ou `5xx` não cancelam a obrigação.
- [x] Enviar original e repetição pelo ingresso normal `/transfer/status` com mTLS; não publicar diretamente no Kafka nem manipular offsets.
- [x] Persistir `events/pacs002-starts.csv` e generalizar `events/replays.csv` para `pacs.008` e `pacs.002`.
- [x] Calcular no simulador, antes da geração, a janela autoritativa e o deadline fixo `generationEnd + max(delay) + drain`; o relatório não deriva tempo do menor registro.
- [x] Usar intervalos semiabertos e impedir originais em `generation_ended_at` ou depois, removendo o registro extra no limite.
- [x] Tratar obrigação que não caiba até o deadline como violação, sem prolongar dinamicamente o experimento para acomodar backlog.
- [x] Manter `targetTxRate` como taxa de pagamentos originais e expor separadamente originais, status, replay `pacs.008` e replay `pacs.002`; remover `total_ingress_started`.
- [x] Validar HTTP do status original separadamente do replay, além de replay ausente, precoce, excedente, incompatível, não `2xx` ou iniciado no deadline/depois.
- [x] Validar pelo fluxo externo que o pagador continua recebendo apenas outcomes compatíveis com seu cenário sob semântica `at-least-once`.
- [x] Manter settlement, fundos, auditoria, outbox e idempotência cobertos pelos testes focados existentes da SPI, sem consultar o banco pelo load-tool.
- [x] Manter status divergente nos testes funcionais negativos focados existentes, fora do workload de performance.
- [x] Registrar um run funcional curto com repetição `pacs.002` habilitada e zero violações.

### Critério de saída

O modelo de repetição está explícito no contrato e na documentação; `run-window.json` e os quatro CSVs em `events/` alimentam o relatório da própria execução; testes focados preservam a idempotência interna; e um run curto prova outcomes externos compatíveis sem acesso direto ao banco.

## Fatia 2C.1 — Caracterização semântica antes da simplificação estrutural (concluída)

**Resultado:** o comportamento útil do workload e seus invariantes de negócio estão protegidos por testes sem transformar flags internas, CSVs, layout do run ou o formato exato do JSON em contrato público.

- [x] Manter como interface pública somente `./run-load-test.sh [--profile NAME] <run-tag>`.
- [x] Caracterizar `mixed-outcomes-smoke` com 1.250 originais no run, 1.000 na janela ativa, 1.000 happy-path, 250 insufficient-funds e 1.250 `pacs.002` originais iniciados.
- [x] Proteger, para o workload curto vigente, 64 seleções de replay `pacs.008` sobre originais e 63 seleções de replay `pacs.002` sobre status originais, preservando a regra exata de 5 por bloco completo de 100.
- [x] Manter as populações, configurações e contagens dos dois tipos separadas e impedir que replay `pacs.008` crie outro `pacs.002` original.
- [x] Não proteger ordinais, identidade dos pagamentos selecionados, coincidência entre conjuntos ou reprodutibilidade de `EndToEndId`.
- [x] Tratar entregas `pacs.002` idênticas sob `at-least-once` como um único resultado lógico final e transformar ausência, status contraditório ou reason contraditório em violação do relatório.
- [x] Provar na SPI que happy-path produz exatamente um `PAYMENT_SETTLED`, insufficient-funds produz zero e status aceito repetido não duplica settlement, fundos, auditoria ou outbox.
- [x] Rejeitar na SPI tanto um segundo settlement idêntico quanto um segundo settlement divergente para o mesmo pagamento.
- [x] Manter settlement como invariante interno da SPI: o relatório do load-tool não inventa observabilidade que o fluxo externo não oferece e não consulta PostgreSQL.

### Critério de saída

Os testes protegem populações, replay e outcomes lógicos externos no load-tool, enquanto os testes focados da SPI protegem settlement e invariantes financeiros. A próxima simplificação pode alterar mecanismos e artefatos internos sem perder essas garantias.

## Fatia 2C.2 — Bundle interno tipado (concluída)

**Resultado:** o layout de um run passou a ter uma representação interna única e testada, sem alterar a CLI, o runner ou o fluxo de execução existente.

- [x] Criar um resolvedor tipado que normalize o diretório do run e derive caminhos fixos para snapshot, plano, janela, relatório e CSVs.
- [x] Validar que `inputs/profile.json` e `inputs/execution-plan.json` foram preparados e que outputs de uma execução anterior ainda não existem.
- [x] Permitir conteúdo preparatório adicional, incluindo certificados, sem tornar seus caminhos parte do bundle nesta fatia.
- [x] Reservar `events/` sem sobrescrever uma tentativa existente.
- [x] Disponibilizar escrita atômica de `sla-report.json`, sem sobrescrita ou relatório parcial visível.
- [x] Manter `validate-profile`, `simulate`, `report`, suas flags e `run-load-test.sh` inalterados.

### Critério de saída

O pacote interno `runbundle` protege o layout, a preparação de uma execução nova e a publicação atômica do relatório. O comando `run` o adota na 2C.3, enquanto a migração do runner público permanece na 2C.4.

## Fatia 2C.3 — Perfil autocontido e comando único de execução (concluída)

**Resultado:** cada perfil declara sua própria identidade e `go-loadtool run --run-dir DIR` executa simulação e relatório em uma única passagem usando `inputs/profile.json` como configuração da carga, enquanto o plano resolvido permanece disponível para a preparação do ambiente.

- [x] Adicionar `name` ao contrato e exigir correspondência entre o nome selecionado e a identidade embutida nos perfis editáveis.
- [x] Fazer o snapshot `inputs/profile.json` ser autocontido e carregar dele nome, conexões, workload, replay, cenários e reporting, sem consultar o plano durante simulação e relatório.
- [x] Adicionar `run --run-dir` usando o resolvedor e as validações da 2C.2, sem aceitar `--profile`, `--config` ou caminhos individuais de artefatos.
- [x] Manter no novo comando os seis overrides mTLS atuais e sua precedência sobre os valores do perfil.
- [x] Executar o simulador, fechar os CSVs e somente então gerar atomicamente `sla-report.json`.
- [x] Não criar relatório quando a simulação ou o report falharem, mantendo artefatos parciais para diagnóstico e proibindo reutilização do diretório.
- [x] Manter temporariamente `simulate`, `report` e todas as flags atuais; o runner continua usando o fluxo antigo nesta fatia.

### Critério de saída

O comando `run` produz sozinho um bundle completo em testes, inclusive usando o renderer real sobre artefatos mínimos.

## Fatia 2C.4 — Migração do runner e remoção da compatibilidade interna

**Resultado:** o runner público usa uma única chamada Go por execução, preserva diagnósticos e exit codes e rejeita relatórios com violações; os comandos e flags internos antigos foram removidos somente após validação funcional do caminho final.

- [x] Migrar `run-load-test.sh` para chamar `go-loadtool run --run-dir` depois da validação, preparação e provisionamento existentes.
- [x] Remover do runner a propagação individual dos caminhos dos CSVs e a chamada separada de relatório.
- [x] Preservar a coleta de diagnósticos após falha do Go, o exit code original, o enriquecimento posterior de `run-window.json` e o layout externo baseado em tag e timestamp.
- [x] Renomear o metadado posterior para `loadtool_finished_at`, mantendo `replay_deadline_at` como fim autoritativo da janela experimental.
- [x] Fazer o runner retornar zero somente para relatório válido com todos os campos `violations` em zero.
- [x] Simplificar configuração do simulador e renderer baseado no layout antes do gate funcional.
- [x] Executar a suíte automatizada e o smoke curto `phase-2c4-single-run-smoke/20260811_142951` pelo caminho final.
- [x] Confirmar no smoke as caracterizações semânticas da 2C.1 e a produção de um único `sla-report.json`.
- [x] Somente após o smoke, remover `simulate`, `report` e as flags internas de caminhos que deixaram de ter consumidores.

### Critério de saída

O comando público permanece `./run-load-test.sh [--profile NAME] <run-tag>`, agora apoiado por uma única execução Go com falha pública para qualquer violação; o caminho interno anterior foi removido depois da evidência funcional.

## Fatia 2C.5 — Bundle de resultados organizado (concluída)

**Resultado:** cada run preserva a mesma evidência funcional em fronteiras legíveis, sem caminhos legados nem política de retenção diferente entre sucesso e falha.

- [x] Fixar `inputs/` para o snapshot byte a byte do perfil e o plano normalizado obrigatório.
- [x] Fixar `events/` para `pacs008-starts.csv`, `pacs002-starts.csv`, `notifications.csv` e `replays.csv`, sem alterar seus schemas.
- [x] Manter somente `run-window.json` e `sla-report.json` na raiz para leitura imediata do manifesto e do resultado agregado.
- [x] Capturar stdout e stderr da chamada Go em `logs/loadtool.log` e a preparação em `logs/prepare-environment.log`, preservando os mesmos arquivos produzidos em sucesso ou falha.
- [x] Separar logs operacionais opt-in em `logs/` dos CSVs e JFRs analisáveis em `diagnostics/`.
- [x] Manter certificados apenas durante a execução e removê-los no cleanup.
- [x] Atualizar testes Go e shell sem adicionar leitura alternativa dos caminhos antigos.
- [x] Apagar os resultados locais no layout antigo e registrar o smoke funcional curto `run-bundle-layout-smoke/20260812_000828` pelo runner público no layout final.

### Critério de saída

Um run curto produz o bundle final e gera seu relatório a partir dos dois inputs e dos quatro CSVs de `events/`; sucesso e falha preservam tudo que chegou a ser produzido, sem placeholders para fases não iniciadas.

## Fatia 2C.6 — Contrato vigente sem compatibilidade histórica (concluída)

**Resultado:** o load-tool aceita somente o bundle e os schemas de artefatos vigentes, sem carregar adapters de um requisito removido de regeneração de relatórios históricos.

- [x] Remover campos e resolução do schema antigo de `run-window.json`.
- [x] Remover headers e branches de parsing antigos dos CSVs de início e replay.
- [x] Usar somente `sender_ispb` como identidade do emissor de replay.
- [x] Unificar o builder e o printer do relatório e consumir sempre os quatro CSVs atuais.
- [x] Remover testes e fixtures dedicados a formatos anteriores, mantendo somente testes do contrato atual.
- [x] Preservar a geração do relatório sobre os mesmos CSVs auditáveis produzidos durante o run.

### Critério de saída

Simulação e relatório continuam funcionalmente iguais para o contrato vigente; não existe fallback, migração, fixture ou promessa documental para bundles e schemas anteriores.

## Fatia 2C.7 — Relatório agregado centrado nos cenários (concluída)

**Resultado:** `sla-report.json` passou a apresentar a decisão do run, o workload funcional e as métricas sem repetir configuração, aliases ou diagnósticos já preservados em outros artefatos do bundle.

- [x] Publicar `valid` como decisão explícita do run e fazer o runner consumir esse booleano sem percorrer recursivamente campos chamados `violations`.
- [x] Manter geração de pagamentos originais como contrato global, com target, esperado, iniciado, TPS efetivo e violações da janela autoritativa.
- [x] Agrupar por cenário os pagamentos e `pacs.002` originais iniciados/aceitos, o outcome esperado/observado e as métricas da janela ativa.
- [x] Preservar semântica `at-least-once`: entregas compatíveis repetidas contam como um resultado lógico; ausência ou qualquer entrega contraditória invalida o cenário.
- [x] Manter replays `pacs.008` e `pacs.002` como populações globais separadas,
  com tentativas iniciadas, aceitas e violações agregadas de quantidade/HTTP;
  propriedades internas de cada replay ficam nos testes do gerador.
- [x] Consolidar performance global em threshold, TPS ativo de originais,
  replays e notificações ao pagador, notificações posteriores à janela ativa e
  percentis de latência arredondados; `pacs.002` causal mantém somente contagens
  totais iniciadas/aceitas por cenário.
- [x] Remover os blocos redundantes `run`, `transactions`, `status_messages`, `load_generation`, `throughput_per_second`, `payer_notification_latency_ms` e `diagnostics`.
- [x] Manter `run-window.json`, `inputs/profile.json` e os quatro CSVs de `events/` como fontes autoritativas de janela, configuração e evidência detalhada.

### Critério de saída

O relatório raiz responde diretamente se o run é válido, quanto tráfego original foi gerado, qual workload e outcome cada cenário produziu, qual carga global de replay ocorreu e quais métricas foram observadas na janela ativa. O runner preserva falha pública para qualquer run inválido, e a auditoria temporal continua disponível nos CSVs sem duplicação no JSON agregado.

## Fatia 3 — Matriz final e handoff para estabilização (concluída)

**Resultado:** a task de estabilização recebe uma matriz funcional validada e sabe quais perfis ou workloads usar em cada experimento de performance.

### Matriz entregue

| Perfil | Papel | Originais e janela | Cenários e distribuição | Carga adicional | Evidência e destino |
| --- | --- | --- | --- | --- | --- |
| `uniform-smoke` | Controle funcional happy-path, sem replay | 2.000/s; warmup `1m`, ativo `1m`, drain `30s` | 100% `ACSC`; 10 pares hot, 40 cold, 80% nos hot | Nenhuma | Baseline funcional existente; não é perfil oficial de 15 minutos |
| `mixed-outcomes-smoke` | Prova funcional rápida do workload oficial | 100/s; warmup `5s`, ativo `10s`, drain `10s` | 80% `ACSC`, 20% `RJCT/AM04`; 80% do tráfego nos pares hot | 5% de replay `pacs.008` + 5% de replay `pacs.002`, ambos após `10s` | `./run-load-test.sh --profile mixed-outcomes-smoke phase-3-workload-matrix-smoke`; run válido `20260812_012859` |
| `mixed-outcomes-2k-15m` | Único workload longo para estabilização e gate oficial | 2.000/s; warmup `1m`, ativo `15m`, drain `30s` | Mesmo contrato funcional `80/20` e mesma distribuição do smoke | 5% + 5%, sem reduzir os 2.000 originais/s | `./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>`; contrato validado automaticamente, execução longa entregue à estabilização |

O perfil longo exercita simultaneamente throughput contratual de pagamentos originais, outcomes de negócio aceito/rejeitado, concentração hot-pair e pressão adicional de retransmissão pelos ingressos normais. Os percentuais `80/20` e `5%` são parâmetros funcionais deliberados da primeira estabilização, não uma alegação de representatividade de produção. A task de performance pode alterá-los em experimentos posteriores, desde que registre o perfil efetivamente executado e não reduza a taxa de originais para acomodar replays.

- [x] Consolidar o baseline happy-path e o workload de resultados mistos, ambos com a distribuição hot-pair existente e com as repetições aplicáveis.
- [x] Refinar antes do handoff as proporções de outcomes e repetições: manter `80/20` e usar `5%` para cada tipo de replay como parâmetros funcionais explícitos.
- [x] Registrar para cada perfil ou workload: comando, objetivo, distribuição, taxa de originais, carga adicional, outcomes esperados e evidência do run funcional curto.
- [x] Identificar `mixed-outcomes-2k-15m` como o único perfil longo que a estabilização deve exercitar; os dois smokes permanecem controles funcionais.
- [x] Entregar a meta de `2.000` pagamentos originais/s, com mensagens repetidas como carga adicional mensurada separadamente.
- [x] Entregar a matriz à task [`estabilizar-teste-carga-budget-cpu.md`](estabilizar-teste-carga-budget-cpu.md), sem concluir capacidade a partir dos runs funcionais curtos.

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
- injetar falhas deliberadas de componente ou rede, incluindo PSP offline/reconnect, restart do gateway, ACK perdido, retry e redelivery operacional; esses cenários pertencem à task [`Engenharia de caos e resiliência operacional`](../Backlog/operacao-testes/engenharia-caos-resiliencia-operacional.md);
- implementar retentativa automática de pagamentos em `ACCEPTED_IN_PROCESS`.
