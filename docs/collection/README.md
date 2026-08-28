# Bruno Transfer Flow

Start the core and the reference demo as described in [`demo/README.md`](../../demo/README.md). To start only the two PSP containers manually, use:

```bash
demo/payment-service-provider/start-psp.sh 11111111 --host-port 8081 --replace
demo/payment-service-provider/start-psp.sh 22222222 --host-port 8082 --replace
```

The launcher automatically creates each PSP settlement account in the local SPI
with an initial balance of `1000`, without resetting an existing account. Use
`--funds-balance VALUE` to choose another initial balance or
`--no-provision-funds` when the account must be prepared separately.

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
