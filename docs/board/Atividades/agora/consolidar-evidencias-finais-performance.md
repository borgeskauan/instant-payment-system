# Consolidar evidências finais de performance

- [ ] Qualificar o runtime final em duas execuções consecutivas e verificáveis

## Objetivo

Preservar uma evidência simples, auditável e reproduzível de que o runtime final sustenta pelo menos `2.000` pagamentos originais por segundo durante `15 minutos`, dentro dos critérios de latência e corretude.

## Trabalho já concluído

* [x] Metodologia, workload, decisões e limitações foram consolidados no [relatório de estabilização](../../../performance/2k-tps-stabilization.md).
* [x] Resultados positivos, negativos e migração de gargalos foram curados nos [achados experimentais](../../../performance/experimental-findings.md).
* [x] A comparação entre os geradores Go e Rust foi documentada separadamente.
* [x] Profile e execution plan finais foram estabilizados.

## Por que a task foi reaberta

A evidência anterior combinava uma execução feita sobre uma revisão base mais um patch ainda não commitado com outra execução feita depois da incorporação desse patch. Essa origem é tecnicamente rastreável, mas exige uma explicação desnecessariamente complexa.

Além disso, a revisão final produziu uma execução com rolling mínimo de `1.995 TPS` seguida por uma execução qualificadora. Selecionar duas aprovações não consecutivas no histórico não demonstra a repetibilidade imediata desejada.

A evidência final será refeita sobre um único commit limpo e por uma campanha atômica de duas runs consecutivas.

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

1. [ ] Escolher e registrar o commit limpo da campanha.
2. [ ] Executar `prepare → run A → validação`.
3. [ ] Se A qualificar, executar imediatamente `prepare → run B → validação`.
4. [ ] Confirmar que as duas runs são consecutivas, equivalentes e aprovadas.
5. [ ] Versionar os artefatos compactos e checksums.
6. [ ] Atualizar o manifesto, o relatório de estabilização e os pontos de entrada canônicos.
7. [ ] Submeter o commit documental à revisão explícita do usuário.
8. [ ] Mover a task para `concluidas` somente depois da aprovação.

## Fora de escopo

* tuning ou alteração do workload durante a campanha;
* escolher amostras favoráveis de campanhas diferentes;
* usar o A/B Go/Rust como substituto das duas qualificações finais;
* depender de diretórios de resultado locais não versionados para verificar a afirmação final.
