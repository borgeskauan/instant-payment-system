# K6 Load Test - Kafka Integration Challenge

## Problem

The K6 load test uses a **deprecated HTTP polling endpoint** `GET /{ispb}/messages` that now returns empty results immediately. Production uses Kafka push notifications instead.

**Impact:**
- Test requires 10 retries with delays (artificial latency)
- Doesn't reflect real architecture
- High failure rates

## Solutions Being Evaluated

We need to bridge K6 tests with Kafka notifications. Evaluating **performance** of two approaches:

### Option 1: HTTP Long Polling
Suspend the HTTP request until notification arrives (or timeout).

**Pros:** Simple K6 integration, native HTTP  
**Cons:** Keeps connections open, resource-intensive at scale

### Option 2: gRPC Streaming
Real-time bidirectional streaming for notifications.

**Pros:** High performance (HTTP/2), efficient, built-in backpressure  
**Cons:** More complex, requires K6 gRPC support

## Performance Metrics to Compare

- **Latency:** P50, P95, P99 from notification to K6 receipt
- **Throughput:** Notifications/second under 8000 VUs
- **Resources:** CPU, memory, connection count on SPI
- **Reliability:** Message delivery success rate

## Next Steps

1. ✅ Document issue
2. ✅ Implement both approaches
3. ✅ Run comparative performance tests (identical load: 8000 VUs, 2min)
4. ⏳ Choose winner based on metrics
5. ⏳ Update load tests

---

**Status:** Planning Phase | **Updated:** March 4, 2026  
**Files:** [load-test/spi-test.js](../load-test/spi-test.js), [NotificationService.java](../spi/src/main/java/br/kauan/spi/domain/services/notification/NotificationService.java)
