# Improvements Backlog

## Architecture

### Separate DICT into its own microservice
DICT (*Diretório de Identificadores de Transações do Pix*) and SPI are distinct systems in the real BCB infrastructure. Currently both run in the same Spring Boot app (`SpiApplication`), sharing the same JVM, database, and port.

**What to do:** Extract `br.kauan.dict.*` into a new `dict` module with its own `pom.xml`, `application.yml`, and Docker service. SPI would then call DICT over HTTP instead of in-process.


## Infrastructure

### Replace H2 with PostgreSQL consistently
SPI has both H2 and PostgreSQL drivers in `pom.xml` and `application.yml` uses H2. Should run PostgreSQL in all environments (already in `docker-compose.yml`) with Flyway migrations enabled.