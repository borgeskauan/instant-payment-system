# Notification Gateway

## Context

PSPs currently consume notifications by directly connecting to the internal `notifications-topic` Kafka topic. This is a security and coupling concern — external parties should not have access to internal infrastructure.

## Solution: `notification-gateway` microservice ✅

A new **`notification-gateway`** service acts as the boundary between the internal Kafka topic and all external consumers (PSPs in production, K6 in load tests). It consumes from Kafka internally and exposes a gRPC server-side streaming API.

```
                          internal                        │                   external
                                                          │
┌─────┐   notifications-topic   ┌──────────────────────┐  │ gRPC stream  ┌────────────────┐
│ SPI │ ───────────────────────>│ notification-gateway │  │ ────────────>│  PSP (prod)    │
└─────┘                         │   (Kafka → gRPC)     │  │              ├────────────────┤
                                └──────────────────────┘  │              │  K6 (load test)│
                                                          │              └────────────────┘
                                                   network boundary
```

**Benefits:**
- PSPs never touch internal Kafka — clean network boundary
- `payment-service-provider` migrates from Kafka consumer to gRPC client
- K6 connects to the same gateway as production (accurate load testing)
- Gateway can be scaled, secured, and versioned independently

## Impact on Existing Services

- **`payment-service-provider`**: `NotificationConsumer` (direct Kafka) → replace with gRPC client to gateway
- **`load-test/spi-test.js`**: `GET /{ispb}/messages` endpoint has been **removed** from SPI → replace with gRPC streaming client
- **`spi`**: `PaymentController` and `NotificationUseCase` removed ✅

## Next Steps

1. ✅ Document architecture decision
2. ⏳ Create `notification-gateway` (Spring Boot + Kafka consumer + gRPC server)
3. ⏳ Migrate `payment-service-provider` to gRPC client
4. ⏳ Update K6 load test to use gRPC
5. ⏳ Update `docker-compose.yml` with new service

---

**Status:** In Progress | **Updated:** March 5, 2026
