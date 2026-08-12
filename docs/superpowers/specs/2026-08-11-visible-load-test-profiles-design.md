# Catálogo visível de perfis do load-test

## Objetivo

Tornar os perfis executáveis imediatamente visíveis no diretório `load-test`, separando configuração operacional da implementação interna em Go.

## Estrutura

O catálogo canônico passa de `load-test/go-loadtool/profiles/` para:

```text
load-test/
├── profiles/
│   ├── mixed-outcomes-smoke.json
│   └── uniform-smoke.json
├── go-loadtool/
├── results/
├── tests/
└── run-load-test.sh
```

Os arquivos são movidos, sem cópia ou symlink no local antigo. `load-test/profiles/` é a única fonte editável dos perfis selecionáveis por nome.

## Comportamento

A interface pública permanece inalterada:

```bash
./run-load-test.sh --profile mixed-outcomes-smoke <run-tag>
```

O runner resolve `--profile NAME` exclusivamente como `load-test/profiles/<name>.json`, preservando validação antecipada de nome, existência, JSON e contrato. O comando interno `validate-profile` usa o mesmo catálogo.

Depois da preparação do run, `go-loadtool run --run-dir` continua consumindo somente o snapshot autocontido `<run-dir>/profile.json`; o novo local do catálogo não se torna parte do bundle nem altera a reprodução de resultados existentes.

## Compatibilidade e validação

- Não adicionar `--config`, caminho arbitrário de perfil ou fallback para o diretório antigo.
- Manter `uniform-smoke` como perfil padrão e preservar byte a byte o conteúdo dos dois JSONs durante a movimentação.
- Atualizar testes e referências internas que apontem para `go-loadtool/profiles`.
- Validar os dois perfis pelo comando Go, executar a suíte Go e os testes shell, além de `bash -n` e `git diff --check`.
- Não executar smoke contra os serviços: a mudança termina antes da criação do snapshot e não altera contrato, carga, simulador ou relatório.
