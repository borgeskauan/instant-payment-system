package br.kauan.kafkaproducer.http;

import br.kauan.kafkaproducer.kafka.PaymentPublisher;
import br.kauan.kafkaproducer.security.PspAuthenticationException;
import br.kauan.kafkaproducer.security.PspAuthorizationException;
import br.kauan.kafkaproducer.security.PspClientCertificateIdentityExtractor;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.util.function.Function;

public class ReactorNettyPaymentServer {

    private static final Logger log = LoggerFactory.getLogger(ReactorNettyPaymentServer.class);

    private final int port;
    private final PaymentPublisher publisher;
    private final SslContext sslContext;
    private final Function<HttpServerRequest, String> identityExtractor;

    public ReactorNettyPaymentServer(int port, PaymentPublisher publisher, SslContext sslContext) {
        this(port, publisher, sslContext, PspClientCertificateIdentityExtractor::extractIspb);
    }

    ReactorNettyPaymentServer(
            int port,
            PaymentPublisher publisher,
            SslContext sslContext,
            Function<HttpServerRequest, String> identityExtractor
    ) {
        this.port = port;
        this.publisher = publisher;
        this.sslContext = sslContext;
        this.identityExtractor = identityExtractor;
    }

    public DisposableServer start() {
        return HttpServer.create()
                .port(port)
                .compress(false)
                .secure(sslProvider -> sslProvider.sslContext(sslContext))
                .route(routes -> routes
                        .post("/transfer", (request, response) ->
                                handle(request, response, publisher::publishPaymentRequest))
                        .post("/transfer/status", (request, response) ->
                                handle(request, response, publisher::publishStatusReport)))
                .bindNow();
    }

    private Publisher<Void> handle(
            HttpServerRequest request,
            HttpServerResponse response,
            PayloadPublisher payloadPublisher
    ) {
        return Mono.fromCallable(() -> identityExtractor.apply(request))
                .flatMap(authenticatedIspb -> request.receive()
                        .aggregate()
                        .asByteArray()
                        .flatMap(payload -> payloadPublisher.publish(authenticatedIspb, payload)))
                .then(Mono.defer(() -> response.status(HttpResponseStatus.OK).send().then()))
                .onErrorResume(PspAuthenticationException.class, error -> {
                    log.warn("PSP authentication failed: {}", error.getMessage());
                    return response.status(HttpResponseStatus.UNAUTHORIZED).send().then();
                })
                .onErrorResume(PspAuthorizationException.class, error -> {
                    log.warn("PSP authorization failed: {}", error.getMessage());
                    return response.status(HttpResponseStatus.FORBIDDEN).send().then();
                })
                .onErrorResume(error -> {
                    log.warn("Failed to publish payment payload: {}", error.toString());
                    return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).send().then();
                });
    }

    @FunctionalInterface
    private interface PayloadPublisher {
        Mono<Void> publish(String authenticatedIspb, byte[] payload);
    }
}
