# Bruno Transfer Flow

Start or reset the complete environment as described in the [reference demo README](../../demo/README.md), then select the `local` environment in Bruno. It provides:

```text
senderPspUrl=http://localhost:8081
receiverPspUrl=http://localhost:8082
```

Set the receiver key if you do not want to use the default:

```text
receiverPixKey=bob@example.com
```

Requests `01`, `02`, and `04` set `senderCustomerId`, `receiverCustomerId`, and `previewReceiverJson` automatically.

Run requests in order:

1. `01 Create sender customer`
2. `02 Create receiver customer`
3. `03 Create receiver PIX key`
4. `04 Preview transfer`
5. `05 Execute transfer`
