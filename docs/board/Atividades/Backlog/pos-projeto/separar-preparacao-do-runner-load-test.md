# Separar preparação do ambiente e execução do load test

- [ ] Tornar o preparador o único responsável por deixar o ambiente pronto para um run

## Contexto

Hoje existem duas fronteiras com nomes e responsabilidades próximas:

- `prepare-performance-environment.sh` recria a stack, remove os volumes,
  aguarda readiness e deixa os componentes disponíveis;
- `run-load-test.sh` ainda chama um preparador interno para interpretar o plano
  e provisionar os participantes antes de executar o workload.

Essa divisão torna ambíguo o significado de "ambiente preparado". Um run pode
informar que a preparação terminou mesmo quando recebeu uma stack reutilizada,
com estado histórico no PostgreSQL ou Kafka. Durante o diagnóstico do load-tool,
essa ambiguidade permitiu iniciar uma tentativa sobre dados Kafka antigos; o
`notification-gateway` abriu leitores históricos em excesso e foi encerrado por
falta de memória direta.

Não deve existir fallback silencioso nem um segundo caminho parcial de
preparação dentro do runner.

## Objetivo

Estabelecer uma fronteira única:

```text
preparador
  → reset da stack e dos volumes
  → build/start
  → readiness e qualificação
  → preparação dependente do perfil, incluindo provisionamento
  → ambiente pronto

runner
  → executa o workload no ambiente previamente preparado
  → coleta diagnósticos e produz o bundle do run
```

O preparador deve receber a identificação do perfil ou outra entrada mínima e
tipada que permita realizar toda a preparação necessária. O runner não deve
recriar a stack, provisionar participantes nem tentar compensar uma preparação
ausente ou incompleta.

## Trabalho

- definir uma única interface pública de preparação para um perfil;
- mover para essa fronteira o provisionamento hoje iniciado pelo runner;
- manter no preparador reset destrutivo, build/start, readiness e qualificação;
- remover do runner a chamada ao preparador interno e as responsabilidades que
  existirem apenas para preparar o ambiente;
- fazer o runner falhar claramente quando o ambiente previamente preparado não
  satisfizer uma pré-condição observável necessária, sem tentar repará-lo;
- eliminar ou renomear scripts e mensagens que deixem duas interpretações para
  "prepare environment";
- documentar em local evidente a sequência oficial:
  `prepare --profile NAME` seguida de `run --profile NAME`;
- testar separadamente o preparador e o runner depois da divisão.

## Critérios de conclusão

- existe um único comando responsável por preparar integralmente o ambiente de
  um perfil;
- o sucesso do preparador significa que reset, subida, readiness, qualificação
  e provisionamento terminaram;
- o runner não remove volumes, não sobe componentes e não provisiona fundos;
- falha do preparador é apresentada ao operador e não aciona um caminho
  alternativo;
- executar somente o runner não produz uma mensagem enganosa de que uma stack
  reutilizada foi integralmente preparada;
- documentação e testes deixam inequívoca a fronteira entre preparação e
  execução medida.

## Fora de escopo

- alterar o contrato dos profiles ou os workloads;
- mudar geração, scheduler, validação funcional ou relatório do load-tool;
- corrigir retenção de memória ou pressão de GC do simulador;
- fazer tuning de SPI, Kafka, PostgreSQL ou `notification-gateway`;
- adicionar novas heurísticas de quiescência interna.
