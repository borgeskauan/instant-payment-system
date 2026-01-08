# Common Commands

## Build & Run
```bash
# Build project
mvn clean install

# Run PSP
cd payment-service-provider
BANK_CODE=Nubank mvn spring-boot:run

# Run SPI
cd spi
mvn spring-boot:run

# Run Kafka Producer
cd kafka-producer
mvn spring-boot:run
```

## Testing
```bash
mvn test
```

## Load Testing
```bash
cd load_test
./run-test.sh
```
