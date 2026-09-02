# Reference demo

This demo turns the payment flow into something you can see and interact with. Alice has an account at Bank A, Bob has an account at Bank B, and the application lets you send a PIX payment between them through the project's core services.

It is a guided example, not part of the benchmarked system. Its purpose is simply to make the payment journey concrete.

## Try it

From the repository root, start the core and the demo:

```bash
./demo/demo start
```

If you have already used the core locally, start with [`./demo/demo reset`](#start-over) instead so both sides begin with matching state.

Then open <http://localhost:4200> and follow the path shown on screen:

1. Choose Alice.
2. Send money to Bob using `bob@example.com`.
3. Wait for the payment to be confirmed.
4. Open Bob's account and see the money arrive.

Alice and Bob are created automatically with an initial balance. Bob's PIX key is also ready to use. Alice starts without a key, but you can register one if you want to send money back from Bob.

![Reference demo customer selection screen](screenshots/payment-app.png)

## Explore the API

The [Bruno collection](../docs/collection/README.md) walks through the same flow as explicit HTTP requests. It creates a separate PIX key for its own run, so it does not interfere with the guided browser path.

The two banks are also available directly at:

```text
Bank A: http://localhost:8081
Bank B: http://localhost:8082
```

## Check the complete flow

The automated smoke test sends a payment through the same public interfaces and verifies that both banks observe the final status and balance changes:

```bash
./demo/demo smoke
```

## Start over

The demo keeps Alice, Bob and their payments only in memory, while the core keeps its payment and notification history. Restarting only one side can therefore leave them out of sync.

To recreate both sides from a clean state, run:

```bash
./demo/demo reset
```

This command deletes the local PostgreSQL and Kafka volumes before starting everything again.

To stop only the demo and leave the core running:

```bash
./demo/demo stop
```

## Scope

The demo is intentionally small. It does not claim production-grade persistence, availability, performance or operational resilience. Those properties belong to the benchmarked core; this application exists only to make its happy path easy to experience.
