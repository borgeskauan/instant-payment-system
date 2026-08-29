# Simplificar o onboarding Linux do load test

- [ ] Permitir que uma pessoa valide o projeto a partir de um clone limpo usando somente Bash e Docker Compose

## Pré-condição

Iniciar esta task somente depois de concluir e congelar as evidências finais produzidas pelo harness atual. O onboarding novo não deve introduzir uma variável enquanto a baseline autoritativa ainda estiver sendo consolidada.

## Objetivo

O critério de sucesso é simples:

```bash
git clone ...
cd instant-payment-system
./load-test/test smoke
```

Esse comando deve preparar uma stack limpa, executar o profile funcional oficial e apresentar claramente o resultado. A task termina quando esse caminho funciona preservando as fronteiras arquiteturais; não existe objetivo de tornar o harness internamente perfeito.

## Interface alvo

```bash
./load-test/test smoke
./load-test/test prepare mixed-outcomes-2k-15m
./load-test/test run qualification-1
./load-test/test down
```

O ambiente preparado seleciona o profile. `run` deriva essa informação do estado preparado e não recebe `--profile` novamente.

## Requisitos

- Expor um único entrypoint público para `smoke`, `prepare`, `run` e `down`.
- Exigir no host somente Linux, Bash e Docker com Compose.
- Construir o load tool sem exigir Rust/Cargo no host e executá-lo host-native para preservar o caminho medido.
- Manter Docker, readiness, provisionamento, certificados e diagnósticos fora do código Rust do load tool.
- Preservar profiles, workload, pacing, networking, recursos, resultados e evidências do harness vigente.
- Falhar com mensagens legíveis quando Docker, preparação ou execução não puderem ser concluídos.

## Validação

- Executar o smoke completo a partir de uma preparação limpa.
- Fazer uma comparação curta e controlada com o harness anterior.
- Repetir qualificação longa somente se a implementação alterar materialmente pacer, networking, execução do binário, afinidade, recursos ou outra parte do caminho medido.

## Fora de escopo

- Suporte nativo a Windows; no Windows, o caminho aceitável continua sendo WSL2.
- Reescrever toda a orquestração em Python ou Rust.
- Eliminar Python, `curl`, OpenSSL ou qualquer ferramenta interna como objetivo independente; elas apenas não podem ser requisitos do host.
- Redesenhar completamente readiness, adapters, diagnósticos ou estrutura dos scripts.
- Criar cobertura exaustiva do harness além do necessário para provar o caminho público e suas falhas relevantes.
- Alterar workloads, SLAs, recursos da stack ou comportamento dos componentes do core.
- Transformar o Compose local em solução de deployment ou alta disponibilidade.
