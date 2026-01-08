# Instant Payment System - Project Overview

## Purpose
A PIX instant payment system implementation following Brazilian Central Bank (BCB) standards. The system simulates the complete PIX payment flow including PSP (Payment Service Providers), SPI (BCB's instant payment infrastructure), and DICT (key directory) components.

## Tech Stack
- **Language**: Java 21
- **Framework**: Spring Boot 3.5.5
- **Architecture**: Hexagonal Architecture (Ports and Adapters)
- **Messaging**: Apache Kafka, Spring Kafka
- **Database**: PostgreSQL, H2 (for testing)
- **HTTP Client**: OpenFeign
- **Build Tool**: Maven
- **Frontend**: Angular (payment-app)
- **Load Testing**: k6

## Main Components
1. **payment-service-provider**: PSP implementation handling customer transactions
2. **spi**: Central bank's instant payment infrastructure simulator
3. **kafka-producer**: Message broker for async communication
4. **payment-app**: Angular frontend application
5. **infra/database**: Database infrastructure

## Code Structure
- Uses Hexagonal Architecture with clear separation:
  - `adapter/input`: Controllers, REST endpoints, Kafka consumers
  - `adapter/output`: Repository implementations, external clients
  - `domain`: Business logic, entities, services
  - `port`: Interfaces defining contracts (input/output ports)
