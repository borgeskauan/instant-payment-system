# Reference demo

This demo lets you experience the payment flow through a small web application.

Alice has an account at Bank A and Bob has an account at Bank B. You can send money from Alice to Bob, follow the payment until it is complete, and then open Bob's account to see the money arrive.

## Start the demo

From the repository root, run:

```bash
./demo/demo start
```

If you have run the project before, use [`./demo/demo reset`](#start-over) instead.

Then open <http://localhost:4200>.

## Make a payment

1. Choose Alice.
2. Send money to Bob using `bob@example.com`.
3. Wait for the payment to be confirmed.
4. Open Bob's account and see the money arrive.

Alice and Bob already have money in their accounts, and Bob's PIX key is ready to use. Alice starts without a key, but you can create one later if you want Bob to send money back.

![Reference demo customer selection screen](screenshots/payment-app.png)

## Start over

To erase the local data and begin again:

```bash
./demo/demo reset
```

This removes the payments created by previous runs.

To stop the demo:

```bash
./demo/demo stop
```

## For developers

The [Bruno collection](../docs/collection/README.md) presents the same journey as a sequence of HTTP requests.

You can also run an automated check of the complete flow:

```bash
./demo/demo smoke
```

The bank APIs are available at:

```text
Bank A: http://localhost:8081
Bank B: http://localhost:8082
```

This is a guided example of the successful payment journey. It is intentionally smaller and simpler than the system evaluated by the project's correctness and performance tests.
