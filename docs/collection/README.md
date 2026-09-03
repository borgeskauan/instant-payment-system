# Try the payment flow with Bruno

This collection tells the same story as the browser demo, one HTTP request at a time: Alice opens an account at Bank A, Bob opens an account at Bank B, and Alice sends Bob a PIX payment of 25.50.

## Before you begin

Start from a clean demo environment:

```bash
./demo/demo reset
```

This command deletes the local data from previous runs. See the [reference demo guide](../../demo/README.md) for more details.

Open `docs/collection` in Bruno and select the `local` environment. You do not need to copy IDs or change variables; the collection carries the information from one request to the next.

## Run the journey

Open the `Alice pays Bob` folder and run its requests in order:

1. `01 Create Alice at Bank A`
2. `02 Create Bob at Bank B`
3. `03 Register Bob's PIX key`
4. `04 Check the recipient`
5. `05 Send 25.50 from Alice to Bob`

The fourth request shows the person associated with Bob's key before any money is sent. The final request then creates the payment using that same key.

The collection uses `bruno-bob@example.com` rather than the key from the browser walkthrough, so the two examples remain independent.
