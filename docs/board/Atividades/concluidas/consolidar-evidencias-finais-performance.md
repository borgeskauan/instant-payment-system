# Consolidar evidências finais de performance

- [x] Qualificar o runtime final em duas execuções consecutivas e verificáveis

## Objetivo

Preservar uma evidência simples, auditável e reproduzível de que o runtime final sustenta pelo menos `2.000` pagamentos originais por segundo durante `15 minutos`, dentro dos critérios de latência e corretude.

## Trabalho já concluído

* [x] Metodologia, workload, resultados, decisões promovidas e limitações foram consolidados em [Performance and evidence](../../../performance.md).
* [x] A comparação entre os geradores Go e Rust foi incorporada somente na medida necessária para interpretar a evidência final.
* [x] Profile e execution plan finais foram estabilizados.

## Por que a task foi reaberta

A evidência anterior combinava uma execução feita sobre uma revisão base mais um patch ainda não commitado com outra execução feita depois da incorporação desse patch. Essa origem é tecnicamente rastreável, mas exige uma explicação desnecessariamente complexa.

Além disso, a revisão final produziu uma execução com rolling mínimo de `1.995 TPS` seguida por uma execução qualificadora. Selecionar duas aprovações não consecutivas no histórico não demonstra a repetibilidade imediata desejada.

A evidência final será refeita sobre um único commit limpo e por uma campanha atômica de duas runs consecutivas.

## Campanha `b7ea138` — não qualificadora

A primeira run qualificou com rolling mínimo de `2.058 TPS`, p99 de `434,433 ms` e corretude integral. A segunda manteve corretude integral e p99 de `841,254 ms`, mas atingiu rolling mínimo de apenas `1.985 TPS`; por isso, a campanha inteira foi rejeitada.

Profile e execution plan foram byte a byte idênticos nas duas runs. Na segunda execução, `958` originais planejados não chegaram a ser iniciados: `651` pertenciam a `31` buckets completos perdidos pelo pacer e os `307` restantes expiraram antes do commit durante preparação ou admissão. As perdas concentraram-se em poucos segundos, enquanto as requests efetivamente iniciadas mantiveram baixa latência de admissão e respostas HTTP saudáveis. Isso descarta o backlog HTTP/2 oculto corrigido pelo commit e restringe a próxima investigação ao scheduling pré-admissão do gerador.

A hipótese de saturação global da CPU do host não foi confirmada. O JFR registrou `machineTotal` próximo de `26%` durante o pior segundo da run B em um host com oito CPUs; existiram picos de `80–98%` em instantes próximos, mas perdas relevantes também ocorreram com uso agregado entre `25–40%`. Os três JFRs produziram valores consistentes entre si, porém a métrica agrega todas as CPUs em amostras de aproximadamente um segundo, enquanto o pacer depende de uma única thread e opera em buckets de `10 ms`. A telemetria disponível não mede CPU por core nem o processo Rust separadamente, portanto não permite excluir starvation curto ou localizado nem atribuir causalidade à CPU do host.

Uma run diagnóstica curta separou as causas sem alterar o workload. Na fase ativa, não houve perda por wakeup tardio nem por canal cheio; `21` slots foram perdidos porque um bucket não ficou pronto e `34` expiraram na admissão HTTP final. A evidência aponta primeiro para o horizonte atual de preparação de `20 ms`, não para prioridade da thread do pacer.

Ao ampliar somente o horizonte de preparação para `50 ms`, mantendo buckets, taxa e deadlines, a repetição diagnóstica executou os `126.000` originais planejados sem misses na fase ativa. O rolling mínimo subiu de `2.058` para `2.079 TPS`, o p99 ficou em `399,581 ms` e a corretude permaneceu integral. O bootstrap ainda perdeu trabalho durante o aquecimento, mas caiu de `1.785` para `860` slots; isso não integra a janela ativa nem altera a conclusão sobre a causa dos misses qualificadores.

Nenhuma das duas runs desta campanha deve ser promovida como evidência final. Uma nova campanha só pode começar depois que essa causa for tratada e a correção estiver em outro commit limpo.

## Campanha `6b13d84` — interrompida na run A

A correção do horizonte de preparação foi commitada e a run A começou com worktree limpa e ambiente recriado. Ela preservou corretude integral, mas não qualificou: executou `1.887.920 / 1.890.000` originais, atingiu rolling mínimo de `1.941 TPS` e p99 de `1.213,256 ms`. O pacer registrou `1.449` slots perdidos na fase ativa. Profile (`721561af...e104a`) e execution plan (`ebef7b92...94508`) preservaram os hashes das campanhas anteriores.

Como a run A violou simultaneamente o piso sustentado e o SLA de latência, a run B não foi executada. Isso preserva o gate atômico: uma segunda amostra não pode recuperar uma campanha cuja primeira execução já falhou.

Essa execução preservou somente o total agregado de misses, insuficiente para distinguir a fronteira responsável pela regressão intermitente. Os contadores causais do gerador passam a ser instrumentação permanente e precisam integrar o próximo commit candidato antes de uma nova campanha.

## Campanha `1351ea5` — qualificadora

A campanha foi executada integralmente sobre o commit limpo `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`. Cada run começou com recriação de containers e volumes pelo preparador oficial, sem mudança de código, configuração ou procedimento entre elas. Profile (`721561af...e104a`) e execution plan (`ebef7b92...94508`) foram byte a byte idênticos.

A run A executou `1.889.369 / 1.890.000` originais, atingiu rolling mínimo de `2.017 TPS` e p99 de `855,202 ms`, com corretude integral. Seus `631` misses ativos foram classificados como `63` por wakeup tardio, `357` por bucket não preparado e `211` na admissão HTTP; não houve canal cheio, canal encerrado nem expiração durante preparação HTTP.

A run B executou `1.890.000 / 1.890.000` originais, atingiu rolling mínimo de `2.079 TPS` e p99 de `265,195 ms`, com corretude integral e zero misses em todas as fronteiras instrumentadas. As duas runs mantiveram HTTP 2xx, outcomes esperados e ausência de contradições ou violações de replay.

Como ambas satisfizeram o piso sustentado, o SLA de latência e a corretude funcional no mesmo commit e em ambientes novos consecutivos, a campanha está aprovada para produzir a evidência final versionada.

## Contrato da campanha qualificadora

### Fonte autoritativa

* um único commit contém todo o runtime exercitado;
* qualquer correção necessária deve ser commitada antes da campanha;
* a worktree permanece limpa durante as duas execuções;
* profile, execution plan, recursos e instrumentação são idênticos;
* nenhuma mudança de código, configuração ou procedimento ocorre entre as runs.

### Sequência obrigatória

```text
commit fixo + worktree limpa
        ↓
prepare ambiente novo
        ↓
run A → validar
        ↓
prepare ambiente novo novamente
        ↓
run B → validar
        ↓
campanha aprovada somente se A e B qualificarem
```

O segundo `prepare` deve ocorrer imediatamente depois da aprovação da run A. Cada preparação recria containers e volumes, aguarda readiness e provisiona o mesmo profile.

Não executar runs adicionais até obter duas amostras favoráveis. Se A ou B falhar por throughput, latência, corretude, replay, gerador ou sistema, a campanha inteira falha. Uma nova tentativa começa novamente pela run A depois de investigar e registrar a causa.

Uma perturbação externa comprovável pode invalidar a campanha, mas exige reiniciar as duas runs. Uso normal do host, variação de scheduling ou resultado desfavorável sem causa externa objetiva não justificam descarte.

## Critérios por run

* rolling mínimo de um segundo maior ou igual a `2.000 TPS` durante toda a janela ativa;
* p99 end-to-end abaixo do threshold interno de `1 segundo`;
* zero outcomes ausentes ou contraditórios;
* zero violações funcionais ou de replay;
* todos os requests originais e replays iniciados aceitos conforme o contrato;
* profile, execution plan, runtime, recursos e instrumentação iguais aos da outra run.

## Evidência versionada

Preservar no Git somente:

* commit exato exercitado;
* profile comum;
* execution plan comum;
* relatório da run A;
* relatório da run B;
* manifesto com origem, critérios, resultados e SHA-256.

Todos os links canônicos devem apontar para artefatos versionados em `docs/**`, nunca para `load-test/results/**` local.

A comparação Go/Rust permanece um estudo separado e não sustenta a afirmação final de capacidade. Runs não qualificadoras podem explicar limitações ou variância, mas não são promovidas como prova de capacidade.

## Ordem de execução

1. [x] Escolher e registrar o commit limpo da campanha.
2. [x] Executar `prepare → run A → validação`.
3. [x] Se A qualificar, executar imediatamente `prepare → run B → validação`.
4. [x] Confirmar que as duas runs são consecutivas, equivalentes e aprovadas.
5. [x] Versionar os artefatos compactos e checksums.
6. [x] Atualizar o manifesto, o relatório de estabilização e os pontos de entrada canônicos.
7. [x] Submeter o conjunto documental à revisão explícita do usuário.
8. [x] Mover a task para `concluidas` somente depois da aprovação.

## Fora de escopo

* tuning ou alteração do workload durante a campanha;
* escolher amostras favoráveis de campanhas diferentes;
* usar o A/B Go/Rust como substituto das duas qualificações finais;
* depender de diretórios de resultado locais não versionados para verificar a afirmação final.
