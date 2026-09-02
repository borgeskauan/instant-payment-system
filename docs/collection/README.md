# Bruno Transfer Flow

Start or reset the complete environment as described in the [reference demo README](../../demo/README.md), then select the `local` environment in Bruno. It provides:

```text
senderPspUrl=http://localhost:8081
receiverPspUrl=http://localhost:8082
```

The collection registers and uses its own receiver key:

```text
receiverPixKey=bruno-bob@example.com
```

Requests `01` and `02` set `senderCustomerId` and `receiverCustomerId` automatically. Request `03` registers `receiverPixKey`; request `04` resolves it; request `05` submits the transfer using the same key.

Start from a clean demo environment before running the folder so the key registration is reproducible.

Run requests in order:

1. `01 Create sender customer`
2. `02 Create receiver customer`
3. `03 Create receiver PIX key`
4. `04 Preview transfer`
5. `05 Execute transfer`
