# Instant Payment System

Simulação local de um fluxo Pix com ingresso HTTP/mTLS, processamento pelo SPI, Kafka, PostgreSQL e entrega de notificações aos PSPs.

## Core

O core benchmarkado é formado por `kafka-producer`, SPI, `notification-gateway` e o load tool Rust. Corretude, capacidade e resultados de performance se referem somente a esses componentes e à infraestrutura que os suporta.

## Quick start

Os comandos exigem Docker com Compose. Prepare uma stack limpa e aguarde a
readiness dos serviços:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-smoke
./run-load-test.sh --profile mixed-outcomes-smoke qualified-smoke
```

O comando remove os volumes do PostgreSQL e Kafka, preserva imagens e build
cache, provisiona os participantes e deixa a stack pronta para o profile. Ele
não gera tráfego. O primeiro run depois dessa preparação é candidato à
qualificação. Um segundo run sem preparar novamente é somente exploratório:

```bash
./run-load-test.sh --profile mixed-outcomes-smoke exploratory-repeat
```

Repita o preparador depois de alterar o código ou antes de iniciar um novo
baseline isolado. Uma repetição iniciada imediatamente após o processo anterior
pode coincidir com o encerramento dos long-polls do Gateway; aguarde até 30
segundos para iniciar outro processo sobre a mesma stack.

Para conferir os containers, a partir da raiz do repositório:

```bash
docker compose -f infra/docker-compose.yml ps
```

Para acompanhar o caminho principal:

```bash
docker compose -f infra/docker-compose.yml logs -f \
  kafka-producer spi notification-gateway
```

## Testes de carga

Prepare o mesmo profile que será executado. Depois do primeiro diagnóstico,
repetições sobre a stack aquecida são exploratórias:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-diagnostic
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-1
sleep 30
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-2
```

Para investigar estabilidade sem pagar o custo do run oficial, use o profile
intermediário de seis minutos:

```bash
./prepare-performance-environment.sh --profile mixed-outcomes-2k-6m
./run-load-test.sh --profile mixed-outcomes-2k-6m loadtool-diagnostic-6m
```

O profile de um minuto continua sendo o feedback rápido; o de seis minutos
serve para diagnóstico de estabilidade; somente o de quinze minutos qualifica
a capacidade oficial.

Cada run de performance só abre a janela ativa depois que todo o trabalho de
warmup observável pelo load-tool termina. Os perfis de performance usam um
bootstrap de 500 pagamentos/s por 60 segundos e depois
1.500 pagamentos/s por 60 segundos, com gate de conclusão de até 120 segundos.
O bootstrap aceita até 30 segundos para concluir cada request enquanto a JVM
está fria; steady e active mantêm o timeout normal de 5 segundos. O gate não
tenta inferir quiescência interna por lag Kafka ou por sleeps fixos.

JFR e diagnósticos do sistema ficam ativos por padrão. Os checkpoints
semânticos amostrados do SPI fazem parte da gravação JFR. Use `--no-jfr` ou
`--no-system-diagnostics` apenas quando o experimento precisar desativá-los.

O gerador registra somente as evidências necessárias para reconstruir a
workload. A seção de geração do relatório informa pagamentos originais
planejados e executados e o piso rolling observado; instrumentação de
infraestrutura permanece fora do hot path Rust. O comando retorna erro somente
quando não consegue concluir tecnicamente a execução ou produzir o relatório.

O workload oficial oferece 2.100 pagamentos originais por segundo durante 15
minutos, além dos replays configurados. O relatório qualifica a capacidade
contra o contrato separado de pelo menos 2.000 pagamentos originais em toda
janela contínua de um segundo; picos acima desse piso não compensam períodos
abaixo dele:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m baseline-buckets
```

Os resultados são gravados em `load-test/results/<run-tag>/<timestamp>/`.

O resultado qualificador, o histórico das decisões e as limitações da medição
estão consolidados no
[relatório de estabilização em 2.000 TPS](docs/performance/2k-tps-stabilization.md).
Os inputs, relatórios A/B e checksums que sustentam a afirmação final estão no
[manifesto de evidência versionada](docs/performance/evidence/2026-08-29/manifest.md).
O contrato de medição permanece descrito em
[Performance SLA](docs/PERFORMANCE_SLA.md).

## Encerrar o ambiente

```bash
docker compose -f infra/docker-compose.yml down
```

O comando não remove volumes.

Para recriar o ambiente do zero, removendo também os volumes:

```bash
docker compose -f infra/docker-compose.yml down -v --remove-orphans
```

Esse comando apaga permanentemente os dados persistidos do PostgreSQL e Kafka.
As imagens Docker são preservadas.

## Reference demo

A [reference demo](demo/README.md) agrupa DICT, dois PSPs simulados e a aplicação Angular para apresentar o fluxo interativamente. Ela não faz parte do core benchmarkado e não recebe suas garantias de performance, durabilidade ou disponibilidade.
