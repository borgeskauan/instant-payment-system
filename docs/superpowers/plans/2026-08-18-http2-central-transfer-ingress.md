# HTTP/2-only Central-Transfer Ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the load-tool-to-`kafka-producer` boundary HTTP/2-only over mTLS, pre-establish every PSP session before warmup, and measure the result without changing the workload or resources.

**Architecture:** Reactor Netty serves only TLS HTTP/2 and adds an authenticated, side-effect-free `GET /health`. Go 1.24 clients enable only HTTP/2, remain isolated by PSP certificate, and all pass a health barrier before the simulator creates `run-window.json`; the same clients then carry all transfer traffic through multiplexed streams.

**Tech Stack:** Java 21, Reactor Netty 1.2.10, Netty mTLS, Go 1.24 `net/http`, Docker Compose, Bash load-test runner, JFR, Kafka and PostgreSQL diagnostics.

## Global Constraints

- Work only in `/tmp/instant-payment-system-reservation-balance-ab` on branch `reservation-balance-ab`; do not merge or push.
- HTTP/2 over TLS is mandatory on the central-transfer boundary; do not advertise, accept or fall back to HTTP/1.1, H2C or HTTP/3.
- Keep mTLS client authentication and PSP identity extraction on `/health`, `/transfer` and `/transfer/status`.
- Keep one independent, long-lived Go client and transport per PSP certificate identity; do not build a custom pool or require exactly one physical connection.
- Retain the per-PSP connection ceiling `32`, request timeout `5s` and idle timeout `90s`; do not add profile, CLI or environment knobs.
- Do not add application retries. Only configured PACS.008/PACS.002 replay obligations may repeat messages.
- Preserve Kafka acknowledgement before HTTP 2xx, payloads, Kafka ISPB keys, eight SPI consumers, batches, transactions, resources and `mixed-outcomes-2k-diagnostic`.
- Prewarm every planned PSP before notification-stream warmup and before `run-window.json` is created. A failed health, mTLS, identity or protocol check produces no PACS event row or business request.
- Do not change event CSV layouts, `sla-report.json` or `run-window.json` schemas.
- Keep `kafka-producer` at one vCPU and `-Dreactor.netty.ioWorkerCount=1` for the controlled diagnostic.
- Execute no 15-minute run and no fallback experiment in this plan.

---

### Task 1: Serve HTTP/2-only mTLS and authenticated health

**Files:**
- Modify: `kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ServerSslContextFactory.java`
- Modify: `kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServer.java`
- Modify: `kafka-producer/src/main/java/br/kauan/kafkaproducer/security/PspClientCertificateIdentityExtractor.java`
- Modify: `kafka-producer/src/test/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServerTest.java`

**Interfaces:**
- Consumes: existing `ServerSslContextFactory.create(Path, Path, Path) -> SslContext`, `PspClientCertificateIdentityExtractor.extractIspb(HttpServerRequest) -> String`, and `PaymentPublisher`.
- Produces: the same `SslContext` factory signature with HTTP/2 ALPN, `ReactorNettyPaymentServer.start()` exposing only `HttpProtocol.H2`, and `GET /health -> 200` after PSP identity extraction without publisher interaction.

- [x] **Step 1: Convert the real server tests to an HTTP/2 client and add RED health/protocol tests**

In `ReactorNettyPaymentServerTest`, build every client used by an mTLS test
with `Http2SslContextSpec` and make the normal request helper explicitly use
H2. This ensures the missing-certificate and untrusted-CA tests fail for their
intended TLS reason rather than because the client omitted ALPN:

```java
private SslContext http2ClientSslContext(TlsMaterial trustedServer, TlsMaterial client) throws Exception {
    return Http2SslContextSpec.forClient()
            .configure(builder -> builder
                    .trustManager(trustedServer.caCertificate().toFile())
                    .keyManager(client.clientCertificate().toFile(), client.clientPrivateKey().toFile()))
            .sslContext();
}

private SslContext http2ClientWithoutCertificateSslContext(TlsMaterial trustedServer) throws Exception {
    return Http2SslContextSpec.forClient()
            .configure(builder -> builder.trustManager(trustedServer.caCertificate().toFile()))
            .sslContext();
}

private SslContext http11ClientSslContext(TlsMaterial trustedServer, TlsMaterial client) throws Exception {
    return SslContextBuilder.forClient()
            .trustManager(trustedServer.caCertificate().toFile())
            .keyManager(client.clientCertificate().toFile(), client.clientPrivateKey().toFile())
            .build();
}

private HttpClient http2Client(SslContext clientSslContext) {
    return HttpClient.create()
            .protocol(HttpProtocol.H2)
            .secure(spec -> spec.sslContext(clientSslContext))
            .responseTimeout(Duration.ofSeconds(5));
}
```

In `setUpTls`, assign the four contexts explicitly:

```java
trustedClientSslContext = http2ClientSslContext(trustedMaterial, trustedMaterial);
clientWithoutCertificateSslContext = http2ClientWithoutCertificateSslContext(trustedMaterial);
untrustedClientSslContext = http2ClientSslContext(trustedMaterial, untrustedMaterial);
trustedHttp11ClientSslContext = http11ClientSslContext(trustedMaterial, trustedMaterial);
```

Use that client for all successful route tests. Add these focused tests:

```java
@Test
void healthAuthenticatesPspWithoutPublishing() {
    FakePaymentPublisher publisher = new FakePaymentPublisher();
    try (RunningServer server = start(publisher)) {
        int status = get(server, trustedClientSslContext, "/health");
        assertEquals(200, status);
        assertEquals(0, publisher.paymentRequests.size());
        assertEquals(0, publisher.statusReports.size());
    }
}

@Test
void rejectsHttp11EvenWithTrustedClientCertificate() {
    try (RunningServer server = start(new FakePaymentPublisher())) {
        assertThrows(RuntimeException.class, () -> HttpClient.create()
                .protocol(HttpProtocol.HTTP11)
                .secure(spec -> spec.sslContext(trustedHttp11ClientSslContext))
                .responseTimeout(Duration.ofSeconds(5))
                .get()
                .uri("https://localhost:" + server.port() + "/health")
                .response()
                .block(Duration.ofSeconds(5)));
    }
}

@Test
void healthReturnsUnauthorizedWhenCertificateIdentityCannotBeExtracted() {
    DisposableServer disposableServer = new ReactorNettyPaymentServer(
            0,
            new FakePaymentPublisher(),
            serverSslContext,
            ignored -> { throw new PspAuthenticationException("missing PSP identity"); }).start();

    try (RunningServer server = new RunningServer(disposableServer)) {
        assertEquals(401, get(server, trustedClientSslContext, "/health"));
    }
}
```

Add an asynchronous publisher gate to prove that HTTP 200 still waits for Kafka-facing completion:

```java
@Test
void transferResponseWaitsForPublisherCompletion() throws Exception {
    FakePaymentPublisher publisher = new FakePaymentPublisher();
    publisher.paymentCompletion = Sinks.empty();

    try (RunningServer server = start(publisher)) {
        CompletableFuture<Integer> response = postResponse(
                server, trustedClientSslContext, "/transfer", "pacs008".getBytes(), null).toFuture();

        assertTrue(publisher.paymentInvoked.await(5, TimeUnit.SECONDS));
        assertFalse(response.isDone());
        publisher.paymentCompletion.tryEmitEmpty();
        assertEquals(200, response.get(5, TimeUnit.SECONDS));
    }
}
```

Have `FakePaymentPublisher.publishPaymentRequest` count down `paymentInvoked` and return `paymentCompletion.asMono()` when the sink is non-null. Factor the existing blocking POST helper over a `Mono<Integer> postResponse(...)` and add a corresponding blocking GET helper.

The response helpers use the already configured H2 client:

```java
private Mono<Integer> postResponse(
        RunningServer server,
        SslContext clientSslContext,
        String path,
        byte[] payload,
        String spoofedAuthenticatedIspb
) {
    return http2Client(clientSslContext)
            .headers(headers -> {
                headers.set("Content-Type", "application/octet-stream");
                if (spoofedAuthenticatedIspb != null) {
                    headers.set("authenticated-ispb", spoofedAuthenticatedIspb);
                }
            })
            .post()
            .uri("https://localhost:" + server.port() + path)
            .send(Mono.just(Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, body) -> body.thenReturn(response.status().code()));
}

private int get(RunningServer server, SslContext clientSslContext, String path) {
    return http2Client(clientSslContext)
            .get()
            .uri("https://localhost:" + server.port() + path)
            .responseSingle((response, body) -> body.thenReturn(response.status().code()))
            .block(Duration.ofSeconds(5));
}
```

- [x] **Step 2: Run the focused server test and verify RED**

Run:

```bash
cd kafka-producer
./mvnw -Dtest=ReactorNettyPaymentServerTest test
```

Expected: FAIL because the server still defaults to HTTP/1.1 and has no `/health`; do not weaken the H2 client to make it connect.

- [x] **Step 3: Configure HTTP/2 ALPN, H2-only routing and health**

Keep `ServerSslContextFactory.create` returning `SslContext`, but construct it through the Reactor HTTP/2 spec:

```java
return Http2SslContextSpec
        .forServer(certificateChain.toFile(), privateKey.toFile())
        .configure(builder -> builder
                .trustManager(trustCertCollection.toFile())
                .clientAuth(ClientAuth.REQUIRE))
        .sslContext();
```

Keep the existing readable-file checks and translate `SSLException` into the existing `IllegalStateException` message.

Configure the listener and route:

```java
return HttpServer.create()
        .port(port)
        .protocol(HttpProtocol.H2)
        .compress(false)
        .secure(sslProvider -> sslProvider.sslContext(sslContext))
        .route(routes -> routes
                .get("/health", this::health)
                .post("/transfer", (request, response) ->
                        handle(request, response, publisher::publishPaymentRequest))
                .post("/transfer/status", (request, response) ->
                        handle(request, response, publisher::publishStatusReport)))
        .bindNow();
```

Implement health without touching `PaymentPublisher`:

```java
private Publisher<Void> health(HttpServerRequest request, HttpServerResponse response) {
    return Mono.fromCallable(() -> identityExtractor.apply(request))
            .then(Mono.defer(() -> response.status(HttpResponseStatus.OK).send().then()))
            .onErrorResume(PspAuthenticationException.class, error -> {
                log.warn("PSP authentication failed: {}", error.getMessage());
                return response.status(HttpResponseStatus.UNAUTHORIZED).send().then();
            });
}
```

Make identity extraction find the TLS handler on either the HTTP/2 stream channel or its parent TCP channel:

```java
private static SslHandler findSslHandler(Channel channel) {
    for (Channel current = channel; current != null; current = current.parent()) {
        SslHandler handler = current.pipeline().get(SslHandler.class);
        if (handler != null) {
            return handler;
        }
    }
    return null;
}
```

Use `findSslHandler(connection.channel())` inside the existing `withConnection` callback. Do not change SAN parsing or authorization inside `KafkaPaymentPublisher`.

- [x] **Step 4: Run focused and complete Kafka-producer GREEN tests**

Run:

```bash
cd kafka-producer
./mvnw -Dtest=ReactorNettyPaymentServerTest test
./mvnw test
```

Expected: both commands exit `0`; the focused suite proves actual H2 mTLS, health without publishing, H1 rejection and publisher-completion gating.

- [x] **Step 5: Commit the server boundary**

```bash
git add kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ServerSslContextFactory.java \
  kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServer.java \
  kafka-producer/src/main/java/br/kauan/kafkaproducer/security/PspClientCertificateIdentityExtractor.java \
  kafka-producer/src/test/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServerTest.java
git commit -m "feat: require HTTP/2 on central transfer ingress"
```

---

### Task 2: Make each Go PSP transport HTTP/2-only

**Files:**
- Modify: `load-test/go-loadtool/internal/sim/http_attempt.go`
- Modify: `load-test/go-loadtool/internal/sim/http_attempt_test.go`
- Modify: `load-test/go-loadtool/internal/sim/simulator.go`
- Modify: `load-test/go-loadtool/internal/sim/simulator_test.go`
- Modify: `load-test/go-loadtool/internal/sim/replay_scheduler_test.go`

**Interfaces:**
- Consumes: Go 1.24 `http.Transport.Protocols`, existing `simulator.post`, existing HTTP-attempt tracing and per-ISPB client map.
- Produces: `newHTTP2Transport(*tls.Config) *http.Transport`, constant `maxConnectionsPerPSP = 32`, strict response-protocol validation, and unchanged event/report contracts.

- [x] **Step 1: Replace the transport tests with explicit HTTP/2-only expectations**

Rename `TestHTTP11TransportBoundsPerPSPPool` to `TestHTTP2TransportIsExclusiveAndBounded` and assert:

```go
transport := newHTTP2Transport(&tls.Config{MinVersion: tls.VersionTLS12})
defer transport.CloseIdleConnections()

if maxConnectionsPerPSP != 32 { t.Fatalf("maxConnectionsPerPSP = %d, want 32", maxConnectionsPerPSP) }
if transport.Protocols == nil || !transport.Protocols.HTTP2() { t.Fatal("HTTP/2 is not enabled") }
if transport.Protocols.HTTP1() { t.Fatal("HTTP/1 fallback is enabled") }
if transport.MaxConnsPerHost != 32 || transport.MaxIdleConnsPerHost != 32 || transport.MaxIdleConns != 32 {
    t.Fatalf("connection bounds = %d/%d/%d, want 32/32/32",
        transport.MaxConnsPerHost, transport.MaxIdleConnsPerHost, transport.MaxIdleConns)
}
if transport.IdleConnTimeout != 90*time.Second { t.Fatalf("IdleConnTimeout = %s, want 90s", transport.IdleConnTimeout) }
```

Replace the HTTP/1.1 reuse test with a real TLS HTTP/2 server using
`httptest.NewUnstartedServer`, `EnableHTTP2 = true` and `StartTLS()`. Clone its
test TLS config into the production constructor:

```go
server := httptest.NewUnstartedServer(handler)
server.EnableHTTP2 = true
server.StartTLS()
defer server.Close()

serverTransport := server.Client().Transport.(*http.Transport)
transport := newHTTP2Transport(serverTransport.TLSClientConfig.Clone())
```

Make two sequential POSTs and assert status 200, `request.ProtoMajor == 2` at
the handler, first acquisition not reused and second acquisition reused.

Add `TestHTTP2TransportRejectsHTTP11Server` using `httptest.NewTLSServer` and assert `client.Do` returns an error rather than an HTTP/1.1 response.

Add `TestPostRecordsProtocolViolation` with a fake round tripper returning:

```go
&http.Response{
    StatusCode: http.StatusOK,
    Proto:      "HTTP/1.1",
    ProtoMajor: 1,
    Body:       http.NoBody,
}
```

Expected assertions: returned status is zero and `s.currentRunError()` contains `used HTTP/1, want HTTP/2`.

Add `TestPostDoesNotRetryTransportFailure`: use a fake round tripper that
increments an atomic counter and returns `errors.New("connection failed")`.
Call `s.post` once and assert status zero, counter exactly one and no run error;
this distinguishes an observed network failure from a new application retry.

- [x] **Step 2: Run the focused Go test and verify RED**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/sim -run 'TestHTTP2Transport|TestPostRecordsProtocolViolation' -count=1
```

Expected: build/test failure because `newHTTP2Transport` and `maxConnectionsPerPSP` do not exist and current POST accepts a fake HTTP/1.1 response.

- [x] **Step 3: Implement the standard-library HTTP/2-only transport**

Replace the HTTP/1.1-specific constructor with:

```go
const maxConnectionsPerPSP = 32

func newHTTP2Transport(tlsConfig *tls.Config) *http.Transport {
    protocols := new(http.Protocols)
    protocols.SetHTTP2(true)
    return &http.Transport{
        MaxIdleConns:        maxConnectionsPerPSP,
        MaxIdleConnsPerHost: maxConnectionsPerPSP,
        MaxConnsPerHost:     maxConnectionsPerPSP,
        IdleConnTimeout:     90 * time.Second,
        TLSClientConfig:     tlsConfig,
        Protocols:           protocols,
    }
}
```

Use `newHTTP2Transport` from `newHTTPClients`. Do not set `ForceAttemptHTTP2` and do not add `golang.org/x/net/http2`.

After reading and closing a successful transport response in `simulator.post`, enforce the response protocol before exposing its status:

```go
if resp.ProtoMajor != 2 {
    s.recordRunError(fmt.Errorf(
        "central transfer response for ISPB %s used HTTP/%d, want HTTP/2",
        ispb, resp.ProtoMajor))
    return result(0)
}
return result(resp.StatusCode)
```

Normal connection errors and timeouts remain status zero without becoming operational `runError`; only an impossible protocol downgrade is promoted to a run error.

Update all fake successful responses in `simulator_test.go` and `replay_scheduler_test.go` to include `Proto: "HTTP/2.0", ProtoMajor: 2, ProtoMinor: 0`. Do not add protocol columns to events.

- [x] **Step 4: Run focused and complete Go GREEN tests**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/sim -run 'TestHTTP2Transport|TestPostRecordsProtocolViolation' -count=1
go test ./...
```

Expected: both commands exit `0`; the real H2 test reuses a connection and the H1-only server cannot be used.

- [x] **Step 5: Commit the strict Go transport**

```bash
git add load-test/go-loadtool/internal/sim/http_attempt.go \
  load-test/go-loadtool/internal/sim/http_attempt_test.go \
  load-test/go-loadtool/internal/sim/simulator.go \
  load-test/go-loadtool/internal/sim/simulator_test.go \
  load-test/go-loadtool/internal/sim/replay_scheduler_test.go
git commit -m "feat: require HTTP/2 from load-tool PSP clients"
```

---

### Task 3: Prewarm all PSP sessions before the workload clock

**Files:**
- Create: `load-test/go-loadtool/internal/sim/central_transfer_prewarm.go`
- Create: `load-test/go-loadtool/internal/sim/central_transfer_prewarm_test.go`
- Modify: `load-test/go-loadtool/internal/sim/simulator.go`

**Interfaces:**
- Consumes: `prewarmHTTP2Clients(context.Context, string, map[string]*http.Client) error`, the Task 2 PSP client map and the server's Task 1 `/health` route.
- Produces: an all-PSP preparation barrier before notification streams and `runwindow.New`, plus the log line `central transfer HTTP/2 prewarm finished: psps=<count> protocol=h2`.

- [x] **Step 1: Write failing focused prewarm tests**

Create tests around fake `RoundTripper`s that return explicit HTTP/2 responses. Cover all planned clients exactly once:

```go
func TestPrewarmCallsHealthExactlyOnceForEveryPSP(t *testing.T) {
    var callsMu sync.Mutex
    calls := map[string]int{}
    clients := map[string]*http.Client{}
    for _, ispb := range []string{"10000001", "20000001"} {
        identity := ispb
        clients[identity] = &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
            if request.Method != http.MethodGet || request.URL.Path != "/health" {
                t.Errorf("%s request = %s %s", identity, request.Method, request.URL.Path)
            }
            callsMu.Lock()
            calls[identity]++
            callsMu.Unlock()
            return http2Response(http.StatusOK), nil
        })}
    }

    if err := prewarmHTTP2Clients(context.Background(), "https://localhost:8001", clients); err != nil {
        t.Fatal(err)
    }
    for ispb := range clients {
        callsMu.Lock()
        count := calls[ispb]
        callsMu.Unlock()
        if count != 1 { t.Fatalf("PSP %s health calls = %d, want 1", ispb, count) }
    }
}
```

Add separate tests for:

- HTTP 500 returns an error containing the ISPB and `status 500`;
- a fake HTTP/1.1 response returns an error containing `used HTTP/1, want HTTP/2`;
- a round-trip error returns an error containing the ISPB and the fake
  transport is called exactly once;
- the same client is retained: run prewarm, assign the unchanged map to a simulator, POST once, and assert the fake transport observed `GET /health` followed by `POST /transfer`.

The shared test helper returns no public metadata:

```go
func http2Response(status int) *http.Response {
    return &http.Response{
        StatusCode: status,
        Proto:      "HTTP/2.0",
        ProtoMajor: 2,
        Body:       http.NoBody,
    }
}
```

- [x] **Step 2: Run the focused prewarm tests and verify RED**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/sim -run TestPrewarm -count=1
```

Expected: build failure with `undefined: prewarmHTTP2Clients`.

- [x] **Step 3: Implement concurrent, side-effect-free health preparation**

Create `central_transfer_prewarm.go` with two focused functions:

```go
func prewarmHTTP2Clients(ctx context.Context, baseURL string, clients map[string]*http.Client) error
func prewarmHTTP2Client(ctx context.Context, healthURL, ispb string, client *http.Client) error
```

`prewarmHTTP2Clients` derives `strings.TrimRight(baseURL, "/") + "/health"`,
starts one goroutine per already bounded profile PSP, cancels sibling work
after an error, waits for every goroutine, and returns one contextual error.
It must not mutate the client map:

```go
func prewarmHTTP2Clients(ctx context.Context, baseURL string, clients map[string]*http.Client) error {
    healthURL := strings.TrimRight(baseURL, "/") + "/health"
    prewarmCtx, cancel := context.WithCancel(ctx)
    defer cancel()

    errors := make(chan error, len(clients))
    var workers sync.WaitGroup
    for ispb, client := range clients {
        workers.Add(1)
        go func(ispb string, client *http.Client) {
            defer workers.Done()
            if err := prewarmHTTP2Client(prewarmCtx, healthURL, ispb, client); err != nil {
                errors <- err
                cancel()
            }
        }(ispb, client)
    }
    workers.Wait()
    close(errors)

    if err, exists := <-errors; exists {
        return err
    }
    return nil
}
```

`prewarmHTTP2Client` creates a GET with the supplied context, executes it
through the given client, drains and closes the body, requires `ProtoMajor ==
2`, then requires `200 <= StatusCode < 300`. It must not call
`simulator.post`, write an event or retry:

```go
func prewarmHTTP2Client(ctx context.Context, healthURL, ispb string, client *http.Client) error {
    request, err := http.NewRequestWithContext(ctx, http.MethodGet, healthURL, nil)
    if err != nil {
        return fmt.Errorf("create central transfer health request for ISPB %s: %w", ispb, err)
    }
    response, err := client.Do(request)
    if err != nil {
        return fmt.Errorf("call central transfer health for ISPB %s: %w", ispb, err)
    }
    _, copyErr := io.Copy(io.Discard, response.Body)
    closeErr := response.Body.Close()
    if copyErr != nil {
        return fmt.Errorf("read central transfer health for ISPB %s: %w", ispb, copyErr)
    }
    if closeErr != nil {
        return fmt.Errorf("close central transfer health for ISPB %s: %w", ispb, closeErr)
    }
    if response.ProtoMajor != 2 {
        return fmt.Errorf("central transfer health for ISPB %s used HTTP/%d, want HTTP/2", ispb, response.ProtoMajor)
    }
    if response.StatusCode < 200 || response.StatusCode >= 300 {
        return fmt.Errorf("central transfer health for ISPB %s returned status %d", ispb, response.StatusCode)
    }
    return nil
}
```

Call the barrier in `Run` after `newHTTPClients` and simulator construction but before `openNotificationStreams` and before `runwindow.New`:

```go
rootCtx, cancel := context.WithCancel(context.Background())
defer cancel()

logPhase("prewarming central transfer HTTP/2 clients: psps=%d", len(httpClients))
if err := prewarmHTTP2Clients(rootCtx, cfg.BaseURL, httpClients); err != nil {
    return fmt.Errorf("prewarm central transfer HTTP/2 clients: %w", err)
}
logPhase("central transfer HTTP/2 prewarm finished: psps=%d protocol=h2", len(httpClients))
```

Reuse this `rootCtx` for the existing notification and experiment contexts. Do not calculate or write the run window before this call. A failure may leave the already-created empty CSV files in the bundle, but it cannot add a PACS row, start a notification stream or publish a business request.

- [x] **Step 4: Run focused and complete GREEN tests**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/sim -run 'TestPrewarm|TestHTTP2Transport|TestPost' -count=1
go test ./...
```

Expected: all tests pass; the focused suite proves the health barrier, H2 validation and client retention without asserting an exact connection count.

- [x] **Step 5: Commit the preparation barrier**

```bash
git add load-test/go-loadtool/internal/sim/central_transfer_prewarm.go \
  load-test/go-loadtool/internal/sim/central_transfer_prewarm_test.go \
  load-test/go-loadtool/internal/sim/simulator.go
git commit -m "feat: prewarm PSP HTTP/2 sessions before load"
```

---

### Task 4: Qualify and measure the HTTP/2-only candidate once

**Files:**
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Modify: `docs/superpowers/plans/2026-08-18-http2-central-transfer-ingress.md`

**Interfaces:**
- Consumes: Tasks 1-3, existing `prepare-performance-environment.sh`, existing `run-load-test.sh`, and immutable control bundle `load-test/results/reservation-balance-kafka-concurrency-eight-diagnostic/20260818_001442`.
- Produces: one qualified HTTP/2 smoke, one complete diagnostic bundle, phase-aligned comparison and a documented next bottleneck without HTTP/1.1 fallback.

- [x] **Step 1: Run all pre-traffic verification from the experimental worktree**

Run:

```bash
cd kafka-producer
./mvnw test
cd ../load-test/go-loadtool
go test ./...
cd ..
for test_script in tests/*-test.sh; do bash "$test_script"; done
cd ..
bash -n load-test/run-load-test.sh load-test/prepare-performance-environment.sh load-test/scripts/*.sh
docker compose -f infra/docker-compose.yml config
git diff --check
```

Expected: Maven has zero failures/errors, every Go package and shell test passes, syntax checks and Compose rendering exit `0`, and `git diff --check` is silent.

- [x] **Step 2: Recreate and qualify the stack once**

From `load-test`, run exactly one preparer invocation:

```bash
GOFLAGS=-buildvcs=false GOCACHE=/tmp/http2-central-transfer-go-cache ./prepare-performance-environment.sh
```

The preparer may use its existing maximum of three smoke attempts. It performs one `docker compose down -v --remove-orphans`, preserves Docker images/build cache, waits for readiness and leaves the stack running. Stop on readiness or correctness failure; do not bypass the qualifier.

Resolve the qualified smoke bundle and verify its `logs/loadtool.log` contains exactly one successful summary with the expected PSP count and `protocol=h2`. Confirm its PACS.008/PACS.002 outcomes, replays and quiescence using the existing smoke qualifier; no HTTP/1.1 compatibility check is deferred to runtime because Task 1 already rejects it with a real TLS server test.

- [x] **Step 3: Execute exactly one measured HTTP/2 diagnostic**

From `load-test`, run once:

```bash
GOFLAGS=-buildvcs=false GOCACHE=/tmp/http2-central-transfer-go-cache ./run-load-test.sh --profile mixed-outcomes-2k-diagnostic reservation-balance-http2-diagnostic
```

Accept exit `0` or `1`; exit `1` is an invalid measured report, while any other exit is operational failure. Do not rerun to improve an unfavorable result and do not run the 15-minute profile.

- [x] **Step 4: Preserve lag and bundle evidence**

Immediately after the runner exits, run once:

```bash
docker exec kafka kafka-consumer-groups --bootstrap-server kafka:9092 --all-groups --describe
```

After exactly 30 seconds, perform the sole later read-only quiescence check:

```bash
sleep 30
./scripts/check-kafka-quiescence.sh
```

Resolve exactly one directory below `results/reservation-balance-http2-diagnostic/`. Reject ambiguity and verify these artifacts are non-empty:

```text
inputs/profile.json
inputs/execution-plan.json
run-window.json
sla-report.json
events/pacs008-starts.csv
events/pacs002-starts.csv
events/notifications.csv
events/replays.csv
diagnostics/container-stats.csv
diagnostics/postgres-statements.csv
diagnostics/postgres-activity.csv
diagnostics/spi-trace.csv
diagnostics/jfr/kafka-producer.jfr
logs/loadtool.log
```

Use `cmp` to prove the candidate and control `inputs/profile.json` files are byte-identical. Verify the candidate load-tool log contains one `central transfer HTTP/2 prewarm finished` line before the `starting warmup plus active load` line.

- [x] **Step 5: Compare the authoritative active windows**

For both bundles, read `[window.generation_started_at, window.active_started_at)` as warmup and `[window.active_started_at, window.generation_ended_at)` as active. Sort event timestamps before rolling calculations and never use totals to compensate for a deficient second.

Record this table from the existing CSV/report/diagnostic fields:

```text
PACS.008 active started / acquired / written / 2xx / timeout
PACS.008 acquisition timeout / written-without-response
rolling minimum / maximum original TPS
PACS.002 active started / 2xx / timeout
happy-path matched / missing / contradictory
insufficient-funds matched / missing / contradictory
PACS.008 and PACS.002 replay violations
kafka-producer active CPU average / maximum
Kafka, SPI and PostgreSQL active CPU average / maximum
TLS handshakes in preparation / warmup / active / drain
top kafka-producer active JFR execution-sample threads and frames
immediate and 30-second payment / status / gateway lag
participant-balance lock calls / rows / total / mean / maximum
native participant-balance waits above one second
```

Use `run-window.json` timestamps as the only phase boundaries. Use `jfr print --json --events jdk.TLSHandshake,jdk.ExecutionSample` on both `diagnostics/jfr/kafka-producer.jfr` files and filter event start times into those same half-open windows. Treat the Go connection fields as request observations and JFR as the aggregate TLS-handshake source; do not infer dial counts as `acquired - reused`.

Correctness gates are strict: every planned PSP was prewarmed over H2, no protocol violation occurred, ACSC and RJCT/AM04 remain correct, replays satisfy their existing contracts and the bundle is complete. If correctness passes but the rolling floor remains below 2,000 TPS, keep HTTP/2 as the required boundary and identify the new measured bottleneck; do not restore HTTP/1.1 or tune another variable in this run.

- [x] **Step 6: Document the result and rerun automated verification**

Append to the active stabilization task:

- the spec/plan and implementation commit IDs;
- smoke attempt history and prewarm evidence;
- candidate bundle and exact active window;
- the comparison table above;
- whether recurring active TLS handshakes, acquisition waits and ingress CPU fell;
- whether keyed/c8 balance-lock behavior remained intact;
- the next bottleneck supported by the result;
- an explicit statement that HTTP/1.1 fallback remains prohibited and no 15-minute run occurred.

Mark completed plan checkboxes, then run fresh final checks:

```bash
cd kafka-producer
./mvnw test
cd ../load-test/go-loadtool
go test ./...
cd ..
for test_script in tests/*-test.sh; do bash "$test_script"; done
cd ..
docker compose -f infra/docker-compose.yml config
git diff --check
git status --short
```

- [x] **Step 7: Commit only the experiment documentation**

```bash
git add docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md \
  docs/superpowers/plans/2026-08-18-http2-central-transfer-ingress.md
git commit -m "docs: record HTTP/2 ingress diagnostic"
```

Confirm the experimental worktree is clean. Leave its commits and result bundle in place; do not merge or push.

## Final-review automated follow-up

The controlled diagnostic and every metric documented above remain tied to the
measured implementation commits `5bce93b`, `4291862`, `2a3d9f1`, and `3e2fcb9`
and to documentation commit `285ecdb`. The later final-review fix restricts the
server ALPN offer to `h2` only and adds publisher-completion, prewarm-abort, and
real concurrent HTTP/2 coverage. This follow-up runs automated verification
only; it does not run the preparer, a smoke or measured workload, a Docker
volume reset, or the 15-minute profile, and it does not alter the recorded
diagnostic metrics.
