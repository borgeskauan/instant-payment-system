# Instant Payment System

Simulação local de um fluxo Pix com ingresso HTTP/mTLS, processamento pelo SPI,
Kafka, PostgreSQL e entrega de notificações aos PSPs.

## Quick start

Os comandos exigem Docker com Compose. Prepare uma stack limpa e aguarde a
readiness dos serviços:

```bash
cd load-test
./prepare-performance-environment.sh
```

O comando remove os volumes do PostgreSQL e Kafka, preserva imagens e build
cache e deixa a stack em execução. Ele não gera tráfego. Repita-o depois de
alterar o código ou antes de iniciar um novo baseline isolado.

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

Depois de uma única preparação, execute o diagnóstico curto quantas vezes forem
necessárias:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-1
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-2
```

Cada run de performance só abre a janela ativa depois que todo o trabalho de
warmup observável pelo load-tool termina. Os perfis oficial de 15 minutos e
diagnóstico usam 1.500 pagamentos/s durante 120 segundos, com gate de conclusão
de até 120 segundos. O gate não tenta inferir quiescência interna por lag Kafka
ou por sleeps fixos.

O protocolo de pull retorna até `15` notificações por chamada; o tamanho não é
configurável pelo PSP nem pelo profile. O relatório registra a distribuição dos
lotes efetivamente recebidos.

JFR, SPI trace e diagnósticos PostgreSQL ficam ativos por padrão. Use
`--no-jfr`, `--no-spi-trace` ou `--no-postgres-statements` apenas quando o
experimento precisar desativá-los.

O workload oficial de estabilização executa 2.000 pagamentos originais por
segundo durante 15 minutos, além dos replays configurados:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-15m baseline-buckets
```

Os resultados são gravados em `load-test/results/<run-tag>/<timestamp>/`.

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

## Fluxo manual com PSPs

Para exercitar o fluxo manualmente com dois PSPs simulados, consulte a
[coleção Bruno](docs/collection/README.md).
