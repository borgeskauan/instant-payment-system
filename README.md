# Instant Payment System

Simulação local de um fluxo Pix com ingresso HTTP/mTLS, processamento pelo SPI,
Kafka, PostgreSQL e entrega de notificações aos PSPs.

## Quick start

Os comandos abaixo partem da raiz do repositório e exigem Docker com Compose.

Suba a stack usada pelos testes de carga, incluindo Grafana, Prometheus e os
exporters:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) \
docker compose -f infra/docker-compose.yml --profile observability up -d --build
```

Confira se os containers estão em execução:

```bash
docker compose -f infra/docker-compose.yml --profile observability ps
```

Para acompanhar o caminho principal:

```bash
docker compose -f infra/docker-compose.yml logs -f \
  kafka-producer spi notification-gateway
```

O Grafana fica disponível em [http://localhost:3000](http://localhost:3000).

## Teste de carga

O smoke funcional mais rápido é:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-smoke local-smoke
```

O workload oficial de estabilização executa 2.000 pagamentos originais por
segundo durante 15 minutos, além dos replays configurados:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-15m baseline-buckets
```

Os resultados são gravados em `load-test/results/<run-tag>/<timestamp>/`.

## Encerrar o ambiente

```bash
docker compose -f infra/docker-compose.yml --profile observability down
```

O comando não remove volumes. Para subir somente os serviços principais, sem a
stack de observabilidade, omita `--profile observability` nos comandos acima.

## Fluxo manual com PSPs

Para exercitar o fluxo manualmente com dois PSPs simulados, consulte a
[coleção Bruno](docs/collection/README.md).
