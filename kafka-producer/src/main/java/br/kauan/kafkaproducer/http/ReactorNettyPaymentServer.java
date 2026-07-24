package br.kauan.kafkaproducer.http;

import br.kauan.kafkaproducer.kafka.PaymentPublisher;

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

public class ReactorNettyPaymentServer {

    private static final Logger log = LoggerFactory.getLogger(ReactorNettyPaymentServer.class);

    private final int port;
    private final PaymentPublisher publisher;
    private final SslContext sslContext;

    public ReactorNettyPaymentServer(int port, PaymentPublisher publisher, SslContext sslContext) {
        this.port = port;
        this.publisher = publisher;
        this.sslContext = sslContext;
    }

    public DisposableServer start() {
        return HttpServer.create()
                .port(port)
                .compress(false)
                .secure(sslProvider -> sslProvider.sslContext(sslContext))
                .route(routes -> routes
                        .post("/{ispb}/transfer", (request, response) ->
                                handle(request, response, publisher::publishPaymentRequest))
                        .post("/{ispb}/transfer/status", (request, response) ->
                                handle(request, response, publisher::publishStatusReport)))
                .bindNow();
    }

    private Publisher<Void> handle(
            HttpServerRequest request,
            HttpServerResponse response,
            PayloadPublisher payloadPublisher
    ) {
        return request.receive()
                .aggregate()
                .asByteArray()
                .flatMap(payloadPublisher::publish)
                .then(Mono.defer(() -> response.status(HttpResponseStatus.OK).send().then()))
                .onErrorResume(error -> {
                    log.warn("Failed to publish payment payload: {}", error.toString());
                    return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).send().then();
                });
    }

    @FunctionalInterface
    private interface PayloadPublisher {
        Mono<Void> publish(byte[] payload);
    }
}
