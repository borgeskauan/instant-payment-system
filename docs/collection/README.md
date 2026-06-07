# Bruno Transfer Flow

Start two PSP containers before running these requests:

```bash
payment-service-provider/start-psp.sh 11111111 --host-port 8081 --replace
payment-service-provider/start-psp.sh 22222222 --host-port 8082 --replace
```

Select the `local` environment in Bruno. It defaults:

```text
senderPspUrl=http://localhost:8081
receiverPspUrl=http://localhost:8082
```

Collection variables to fill while running the flow:

```text
receiverPixKey=bob@example.com
```

Requests `01`, `02`, and `04` set `senderCustomerId`, `receiverCustomerId`, and `previewReceiverJson` automatically as runtime variables.

Run requests in order:

1. `01 Create sender customer`
2. `02 Create receiver customer`
3. `03 Create receiver PIX key`
4. `04 Preview transfer`
5. `05 Execute transfer`

Watch the backend flow:

```bash
docker compose -f infra/docker-compose.yml logs -f kafka-producer spi notification-gateway
docker logs -f psp-11111111
docker logs -f psp-22222222
```
