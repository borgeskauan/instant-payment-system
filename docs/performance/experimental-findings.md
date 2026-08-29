# Achados experimentais da estabilização

## Propósito

Este apêndice preserva o conhecimento obtido nos experimentos intermediários que alteraram uma decisão de arquitetura, performance ou metodologia. Ele não é um catálogo dos `255` diretórios de resultado e não promove diagnóstico a benchmark. A prova final de capacidade permanece no [relatório de estabilização em 2.000 TPS](2k-tps-stabilization.md).

Os artefatos das execuções foram tratados como fonte primária. Relatório, profile, plano, timestamps, CSVs, estatísticas PostgreSQL, JFR e perfis do runtime foram usados conforme disponíveis. O [caderno histórico](../board/Atividades/concluidas/estabilizar-teste-carga-budget-cpu.md) serviu para localizar comparações e decisões, não para preencher metadata ausente.

O grau de confiança usado abaixo significa:

- **alto:** mecanismo diretamente medido e reproduzido, ou resultado final repetido em condições equivalentes;
- **médio:** A/B limpo ou sequência coerente, mas sem contrabalançar ordem, host ou todas as variáveis ambientais;
- **diagnóstico:** atribui custo ou elimina uma hipótese, porém a instrumentação ou a forma da execução impede inferir capacidade.

## 1. Média não representa carga sustentada

**Hipótese:** quantidade total e média próximas de `2.000 TPS` seriam suficientes para caracterizar a workload.

**Evidência:** `baseline-buckets/20260814_093850` iniciou `1.799.999` pagamentos ativos e registrou média de `1.999,999 TPS`, mas o mínimo rolling foi zero, o máximo chegou a `7.105 TPS` e o p99 de outcome foi `895,655 s`. Trabalho atrasado era recuperado por picos posteriores. Após separar oferta e piso, o primeiro run a `2.100/2.000 TPS` ainda caiu a `1.550 TPS`; sua repetição, sem mudança de código, alcançou `2.062 TPS` e p99 de `513,842 ms`.

**Decisão:** usar buckets absolutos sem carry-over e validar o menor throughput em qualquer janela contínua de um segundo. A oferta de `2.100 TPS` cria margem para provar o piso de `2.000 TPS`, mas nunca compensa uma janela anterior abaixo dele.

**Confiança:** alta para a inadequação da média e do catch-up; média para qualquer conclusão sobre um run curto isolado próximo do limite.

## 2. Isolamento e warmup fazem parte do experimento

**Hipótese:** lag Kafka zero e um warmup único seriam suficientes para iniciar o active em estado comparável.

**Evidência:** uma repetição do PACS.002 recebeu uma notificação persistida pelo run anterior mesmo com lag Kafka zerado. Ela foi excluída e repetida somente após reset completo. Nos testes de aquecimento, `60 s @ 1.000 TPS` deixou na primeira janela ativa `3,500/2,702/3,597 s` de compilação em Producer/SPI/Gateway, contra `0,155/1,073/0,920 s` numa JVM estabilizada. `120 s @ 1.000 TPS` ainda falhou para Producer e Gateway. `120 s @ 2.000 TPS` precisou de `90,487 s` de gate; `120 s @ 1.500 TPS` reduziu o gate para `74,516 s` e passou o critério JFR das três JVMs. Mais tarde, o controle de stack fria com timeout uniforme de `5 s` produziu `7.688` timeouts PACS.008 no bootstrap; dar somente ao bootstrap `30 s` eliminou esses timeouts e fechou as obrigações em `10,104 s`.

**Decisão:** toda medição qualificadora recria containers e volumes. O warmup oficial tem bootstrap `500 TPS / 60 s / 30 s`, steady `1.500 TPS / 60 s / 5 s` e gate observável de até `120 s`. Lag interno não é usado como prova de quiescência.

**Confiança:** alta para contaminação entre runs e para a necessidade do bootstrap; média para os valores exatos de warmup, pois foram homologados neste host e nestas JVMs.

## 3. O primeiro limite estava no ciclo das conexões

**Hipótese:** o SPI ou o PostgreSQL já eram o primeiro gargalo do baseline.

**Evidência:** `tls-handshake-diagnostic/20260815_143609` observou `6.717` handshakes TLS 1.3, incluindo `4.550` durante o active, enquanto o ingresso usou `101,47%` de CPU e somente `1.243/14.226` tentativas HTTP obtiveram 2xx. No A/B seguinte, um pool HTTP/1.1 de 32 conexões por PSP reduziu handshakes por 100 tentativas de `47,22` para `2,87`, elevou respostas 2xx para `149.640` e reduziu CPU média do ingresso para `81,74%`. No trecho estável final, o ingresso caiu a `39,89%` enquanto PostgreSQL chegou a `101,54%`.

**Decisão:** conexões persistentes e prewarm autenticado são parte do modelo do PSP; o caminho final usa HTTP/2 obrigatório. O gargalo só passou a ser atribuído ao SPI/PostgreSQL depois que o ingresso deixou de renovar conexões continuamente.

**Confiança:** alta para o custo da renovação de conexões; média para atribuir toda a melhora posterior especificamente ao HTTP/2, porque pooling, prewarm e maturidade do restante da stack evoluíram em etapas.

## 4. A migração de gargalos foi observável nos recursos

| estágio | ingresso HTTP | PostgreSQL | interpretação |
| --- | ---: | ---: | --- |
| primeiro diagnóstico | `99,86%` | `76,82%` | o front door impedia que a carga alcançasse o core |
| fim estável do A/B de pooling | `39,89%` | `101,54%` | a capacidade liberada expôs o banco |
| primeira qualificação final | `30,23%` | `41,09%` | remoções estruturais devolveram margem aos dois componentes |
| repetição final | `30,20%` | `39,78%` | a distribuição permaneceu estável |

No baseline de ingresso, PostgreSQL não mostrou deadlock, blocker, arquivo temporário ou leitura física relevante; a pressão observada estava em CPU e WAL. No estado final, Gateway, Kafka e SPI usaram respectivamente cerca de `24%`, `12%` e `10%` de um core. Isso demonstra que o tuning não apenas deslocou saturação indefinidamente: o estado qualificado terminou com folga distribuída.

**Confiança:** alta para as médias amostradas; elas não formam limite contínuo entre amostras nem prova sob um cgroup agregado de três CPUs.

## 5. Wall-time SQL não é CPU intrínseca

**Hipótese:** a transição PACS.002 era o maior consumidor do PostgreSQL porque liderava tempo acumulado de parede.

**Evidência:** em ambiente quiescente, o update real de `350` pagamentos consumiu aproximadamente `4 ms` depois de retirar a criação artificial dos fixtures. No diagnóstico descartável com `log_executor_stats`, o ciclo antigo de outbox/delivery respondeu por `31,796 s`, ou `74,38%`, da CPU de executor atribuída. Lock, transição e saldo diretamente associados ao PACS.002 somaram `2,836 s`, ou `6,63%`. O update PACS.002 teve média de `23,173 ms` de CPU para `119,914 ms` de wall-time por chamada, sem blocking PID.

**Decisão:** usar `pg_stat_statements` para localizar trabalho e CPU nativa, locks, I/O e plano para explicar o custo. A otimização passou do settlement isolado para o lifecycle compartilhado de notificações.

**Confiança:** diagnóstico. O profiler gerou `41 MB` de log, perturbou a workload e não cobre parser, planner, commit ou processos de background.

## 6. Batching reduziu transações, mas não eliminou o problema estrutural

**Hipótese:** o batch Kafka existente já implicava persistência em batch no Gateway.

**Evidência:** o listener antigo recebia e persistia um record por transação. A mudança para lista por poll processou `5,48` vezes mais deliveries com `7,7%` menos commits globais; commits por notificação caíram de `3,175` para `0,535`, e amostras `WALWrite/WalSync` do insert caíram de `165` para zero. Persistir ACKs em batch reduziu chamadas em `99,0%`, tempo por row em `66,6%` e commits em `87,4%`, mas não reduziu WAL por row e ainda preservava todo o lifecycle de ACK.

Reduzir a concorrência da persistência do Gateway de dois consumers para um elevou rows por chamada de `109,442` para `214,443`, reduziu tempo por row de `0,542` para `0,215 ms` e CPU do Gateway de `24,98%` para `13,57%`. O PostgreSQL continuou em um core e o limite migrou para etapas posteriores.

**Decisão:** manter batching real e serialização por fluxo na stack única. Posteriormente, remover ACK, lease e redelivery ativo por meio do Pull foi preferível a continuar otimizando um lifecycle desnecessário.

**Confiança:** alta para os ganhos locais; média para efeito end-to-end, pois os primeiros runs ainda continham catch-up e deadlines antigos.

## 7. Ganho local não implica ganho sistêmico

**Hipótese:** tornar uma query dominante mais barata elevaria automaticamente outcomes e throughput.

**Evidência PACS.002:** agrupar updates por outcome reduziu tempo SQL da transição em `69,69%`, custo por row em `66,33%` e pior execução em `98,32%`. Mesmo assim, o A/B limpo produziu `11,04%` menos outcomes e `18,68%` mais ausências. A correlação por pagamento mostrou que o trecho POST PACS.002 → notificação final ficou mais rápido; a perda ocorreu antes, enquanto outras escritas disputavam o mesmo core.

**Evidência PACS.008:** pré-selecionar IDs antes do insert reduziu mais de `65%` do SQL isolado, mas a leitura adicionada elevou o SQL global em `5,61%` e o p99 end-to-end em `26,51%`.

**Decisão:** manter a simplificação PACS.002 por ser localmente melhor e semanticamente menor, mas não creditá-la pela capacidade final. No PACS.008, preservar `INSERT ... ON CONFLICT DO NOTHING` e consultar somente conflitos.

**Confiança:** alta para os mecanismos locais; média para a causa exata da variação sistêmica, pois checkpoint, scheduling e dinâmica das filas não foram isolados individualmente.

## 8. Layout físico foi decidido pelo mecanismo, não pela cauda de um run

**Hipótese:** reduzir tamanho de rows e favorecer HOT updates diminuiria pressão no PostgreSQL.

**Evidência:** `fillfactor=50` levou updates HOT de `22,86%` para `100%`, mas elevou heap mais índices em `46,98%` e deixou outcomes praticamente iguais (`-0,02%`). A compactação de pagamentos e auditoria reduziu tempo SQL por row em `12,89%/10,19%` e WAL por row em `13,09%/6,68%`. Remover índices técnicos de auditoria sem consumidores reduziu seu insert entre `38,25%` e `52,43%` por row e aproximadamente `46%` de WAL por row; a variação end-to-end entre repetições era maior que a diferença entre schemas.

**Decisão:** manter `fillfactor=50`, representações compactas e apenas índices ligados a fatos de negócio. Esses ganhos físicos reduzem custo reproduzível, mas não são apresentados como causa isolada da qualificação.

**Confiança:** alta para HOT, tamanho, SQL e WAL; baixa para atribuir diferenças de latência end-to-end a cada mudança física.

## 9. A arquitetura de delivery foi simplificada por etapas mensuráveis

**Hipótese inicial:** otimizar cada mutação do reliable push seria suficiente.

**Evidência estrutural:** uma notificação atravessava insert e publicação da outbox, insert e claim da delivery e ACK, gerando cerca de `5,2 KiB` de WAL por notificação e duplicando o payload imutável.

**Fase de índice mínimo:** substituir a delivery larga por `delivery_index` reduziu o SQL desse caminho em `67,06%` e seu WAL em `48,75%`, mas PostgreSQL continuou saturado.

**Fase de reconciler:** quatro varreduras saudáveis que não encontraram lacunas ainda consumiram `14,741 s` de SQL. Num run longo, dez scans sem lacunas chegaram a `519,7 s`, mostrando que o custo crescia com o histórico, não com falhas reais.

**Fase de notificação imutável:** remover `PENDING/PUBLISHED` eliminou `27,703 s` de SQL e `152.467.009 B` de WAL do update de publicação; outcomes ausentes caíram `56,64%` naquela comparação, mas o banco continuou saturado.

**Checkpoint ordenado:** reduziu o reconciler longo de `519,7` para `69,974 s`, porém introduziu contenção no contador global e ainda exigiu scan do índice de delivery.

**Kafka durável:** contra o híbrido ordenado, reduziu p99 de `1.767,027` para `540,141 ms`, SQL exportado em `61,49%`, WAL em `21,86%` e CPU média do PostgreSQL em `22,006 pp`. O mínimo rolling ainda caiu a `1.564 TPS`, isolando o problema remanescente no gerador/host, não em rejeição HTTP ou outcome.

**Decisão:** PostgreSQL garante a criação atômica da obrigação, Kafka mantém o log operacional por sete dias, o PSP conserva seu cursor e o Gateway serve Pull sem estado durável próprio de progresso.

**Confiança:** alta para remoção de trabalho PostgreSQL; média para comparar latência absoluta entre fases, pois o core e o gerador também evoluíram ao longo da sequência.

## 10. Limites configurados não são tamanhos de lote observados

**Hipótese:** aumentar `max.poll.records`, `fetch.min.bytes` ou o limite de Pull produziria diretamente o lote configurado.

**Evidência PACS.002:** elevar `max.poll.records` de `220` para `500` aumentou o lote médio de `129,084` para `162,806`, com máximo `339`, reduziu callbacks em `20,37%` e o tempo agregado dos callbacks em `24,11%`. Elevar `fetch.min.bytes` de `16` para `20 KiB` mudou a média somente para `164,354` e piorou p99 de `489,036` para `668,482 ms`.

**Evidência PACS.008:** reduzir `fetch.min.bytes` de `128` para `56 KiB` preservou mediana `165`, mas reduziu p99/máximo do lote de `281/493` para `235/350`, p99 do callback de `105,511` para `72,191 ms` e p99 end-to-end de `566,941` para `386,178 ms`.

**Evidência Pull:** na varredura histórica `1/10/15/20/500`, limite `15` produziu média real `11,506` e comportamento mais regular que limites maiores. No caminho final Kafka, `68.849` respostas não vazias tiveram média `1,189`, p95 `2` e máximo `3`.

**Decisão:** `500`, `56 KiB`, `16 KiB` e Pull `15` são tetos e condições de formação homologados, não promessas de cardinalidade. Toda mudança futura deve medir a distribuição real e a cauda.

**Confiança:** alta para as distribuições observadas; média para tratar cada valor como ótimo fora desta taxa, payload e topologia.

## 11. O Go mostrou capacidade ocasional, mas pouca margem temporal

**Hipótese:** reduzir alocações e escrita síncrona do Go seria suficiente para tornar a qualificação longa repetível.

**Evidência:** um run longo passou com mínimo rolling `2.003 TPS`, mas outros runs funcionalmente corretos produziram `1.330`, `1.844`, `1.934`, `1.966` e `1.986 TPS`. Diagnósticos curtos frequentemente passavam e não previam a janela de 15 minutos. No diagnóstico longo, o runtime atingiu `3.000` goroutines, `657` GCs, `57,5 s` de CPU de GC e `1.173 s` de espera acumulada em mutexes. A espera estava distribuída entre admissão, POST, PACS.002 e Pull; não havia um único mutex saudável cuja remoção resolvesse o desenho.

**Decisão:** não continuar acumulando otimizações locais sobre pools fixos, estado compartilhado e responsabilidades concentradas. O A/B final preservou Go como comparação funcional, não como gerador qualificador.

**Confiança:** alta para a variância histórica e o perfil; a comparação não é uma afirmação geral sobre a linguagem Go.

## 12. O resultado Rust veio do ownership, não apenas da linguagem

**Hipótese:** uma thread nativa com buckets de `1 ms`, mais spin, canal maior ou pinning bastaria para pacing preciso.

**Evidência:** o protótipo final de `1 ms` perdeu `30.877/246.000` slots. Buckets de `10 ms` reduziram misses para `1.170`; um coordenador, para `104`. Canal maior repetiu `21` misses, pinning produziu `29`, e spin de `1 ms` ainda deixou `12` ao custo de `16,012 s` de CPU ativa. O diagnóstico de espera encontrou só um retorno tardio do sleep, mas `26` misses antes do commit.

Com o planner compartilhado preparando o request antes da fronteira, todos os `246.000` slots foram executados, p99 do pacer caiu para `0,244 ms`, p99 do início HTTP para `0,228 ms` e user CPU de variantes imediatamente anteriores caiu de aproximadamente `211–220 s` para `37,570 s`. O cutover final preservou zero misses e RSS de `59,6 MiB`.

**Decisão:** pacer tem um owner, o trabalho é preparado antes do bucket e filas/capacidades são explícitas. Afinidade, scheduler especial, spin longo e filas maiores não fazem parte do contrato.

**Confiança:** alta para a evolução interna; média para separar quanto do A/B final veio da linguagem, do runtime ou da nova arquitetura como um todo.

## 13. Profiling deve ser tratado como intervenção

**Hipótese:** perfis mais detalhados sempre ajudam sem alterar materialmente o experimento.

**Evidência:** `log_executor_stats` gerou `41 MB` e forte variação de geração. Heaptrack registrou `47,59` milhões de alocações, mas elevou RSS para `491 MiB` e p99 para `718,061 ms`, contra aproximadamente `59,6 MiB` e `253,867 ms` no diagnóstico normal. O perfil de CPU Rust não apresentou símbolo dominante: `malloc` ficou em `1,36%`, `free` em `1,19%` e HPACK em `0,64%`.

**Decisão:** usar instrumentação intrusiva somente para atribuição e nunca como amostra qualificadora. Não implementar buffer pool, allocator customizado ou JSON manual apenas por contagem de alocações.

**Confiança:** alta para o overhead observado; o perfil perturbado não quantifica o custo normal de cada alocação.

## 14. A qualificação final tem margem; 4k ainda não

**Evidência a 2k:** duas execuções limpas iniciaram `1.890.000/1.890.000` originais, mantiveram mínimo rolling `2.079 TPS`, p99 de `268,134/259,956 ms`, zero outcome ausente ou contraditório e cerca de `1,18/1,16 vCPU` médios na stack.

**Evidência a 4k:** diagnósticos curtos iniciaram quase toda a carga e preservaram outcomes, mas o mínimo rolling ficou entre `3.920` e `3.960 TPS` e p99 entre `1,36` e `2,45 s`. Elevar `max.poll.records` PACS.008 de `500` para `1.000` não eliminou a fila; p99 do callback cresceu aproximadamente `153 → 250 ms` e mais trabalho terminou depois do active.

**Decisão:** declarar somente a capacidade repetida de `2.000 TPS`. `4.000 TPS` localiza a próxima fronteira no consumer PACS.008, mas não autoriza aumentar concorrência, instâncias ou recursos dentro desta task.

**Confiança:** alta para 2k na stack única; alta para a não qualificação de 4k; inexistente para escala multi-instância, que não foi exercitada.

## Limitações transversais

- Muitos experimentos são sequenciais, não A/B contrabalançados; ordem, JIT, checkpoints e ruído do host podem influenciar a cauda.
- O core e o gerador evoluíram durante a campanha. Comparações distantes no tempo demonstram migração arquitetural, não efeito isolado de uma linha de código.
- O gerador compartilhou o host com a stack. Seu overhead foi medido e reduzido, mas não reproduz uma máquina externa dedicada.
- `docker stats` e activity sampling são amostras; não garantem ausência de picos entre observações.
- Um broker, uma instância por serviço e um consumer por fluxo qualificam o MVP local, não HA ou concorrência multi-instância.
- O archive restaurou quase toda a evidência citada. `baseline-buckets/20260814_023552` continua ausente e não foi substituído por outro run de nome semelhante.
- Tags e timestamps não identificaram com segurança código e intenção em todos os runs; a correção foi separada para a task futura [Rastrear identidade e intenção dos experimentos de carga](../board/Atividades/Backlog/operacao-confiabilidade/rastrear-identidade-intencao-experimentos-load-test.md).

## Regra de interpretação

O conhecimento durável desta campanha não é “cada otimização melhorou o TPS”. É:

```text
medir a workload real
→ remover o primeiro custo acidental dominante
→ observar onde o limite migrou
→ validar o mecanismo local
→ repetir o caminho end-to-end
→ manter somente ganhos reproduzíveis ou simplificações estruturalmente justificadas
```

Essa regra explica por que algumas mudanças permaneceram mesmo sem ganho imediato de throughput, por que outras foram descartadas apesar de microbenchmarks positivos e por que a qualificação só foi declarada depois de duas execuções longas equivalentes.
