# Consolidação do backlog

## Objetivo

Eliminar a duplicidade entre `docs/board/Backlog/` e
`docs/board/Atividades/Backlog/`, mantendo um único backlog em
`docs/board/Atividades/Backlog/`.

## Estrutura de destino

Os três arquivos agregadores do backlog antigo se tornam diretórios de
categoria. Cada seção de tarefa se torna um arquivo próprio:

```text
docs/board/Atividades/Backlog/
├── infra-documentacao/
│   ├── infraestrutura-deploy.md
│   ├── control-panel-psps.md
│   └── documentacao-geral-projeto.md
├── operacao-testes/
│   ├── auditoria-rejeicoes-entrada.md
│   ├── engenharia-caos-resiliencia-operacional.md
│   ├── estabilizar-teste-carga-budget-cpu.md
│   ├── gating-prontidao-microservicos.md
│   └── observabilidade-operacional-fluxo-pix.md
└── produto-dominio/
    ├── consistencia-dict-oficial-cadastro-local-chaves-psp.md
    ├── consultas-auxiliares-simuladas.md
    ├── contrato-erros-http-kafka-producer.md
    ├── contrato-preview-execucao-psp.md
    ├── retentativa-liquidacao-pagamentos-em-processamento.md
    └── validacoes-dict.md
```

As três tarefas que já estavam individualizadas também ficam agrupadas pela
responsabilidade: auditoria de rejeições e caos em `operacao-testes/`, e
retentativa de liquidação em `produto-dominio/`.

## Regras da migração

- Preservar o conteúdo e os checkboxes de cada tarefa.
- Promover o título da seção de `##` para `#` no arquivo individual.
- Não criar índices ou arquivos `README` nas categorias.
- Não revisar, reagrupar nem reinterpretar o escopo das tarefas.
- Atualizar referências internas para os novos caminhos.
- Remover os três arquivos agregadores e `docs/board/Backlog/` após a divisão.
- Preservar a movimentação não commitada de `auditoria-rejeicoes-entrada.md`.
- Não criar commits.

## Referências conhecidas

As referências em
`docs/board/Atividades/concluidas/cenarios-realistas-reprocessamento-load-tool.md`
devem passar de `docs/board/Backlog/operacao-testes.md` para
`docs/board/Atividades/Backlog/operacao-testes/estabilizar-teste-carga-budget-cpu.md`.

## Verificação

- Conferir que as onze seções do backlog antigo resultaram em onze arquivos.
- Conferir que os textos e checkboxes foram preservados.
- Procurar referências restantes a `docs/board/Backlog/` e aos três agregadores.
- Confirmar que `docs/board/Backlog/` não permanece no resultado.
- Executar `git diff --check`.
