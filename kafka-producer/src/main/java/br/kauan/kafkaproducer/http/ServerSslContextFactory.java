package br.kauan.kafkaproducer.http;

import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import reactor.netty.http.Http2SslContextSpec;

import javax.net.ssl.SSLException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ServerSslContextFactory {

    private ServerSslContextFactory() {
    }

    public static SslContext create(
            Path certificateChain,
            Path privateKey,
            Path trustCertCollection
    ) {
        requireReadable(certificateChain, "TLS certificate chain");
        requireReadable(privateKey, "TLS private key");
        requireReadable(trustCertCollection, "TLS trust certificate collection");

        try {
            return Http2SslContextSpec
                    .forServer(certificateChain.toFile(), privateKey.toFile())
                    .configure(builder -> builder
                            .trustManager(trustCertCollection.toFile())
                            .clientAuth(ClientAuth.REQUIRE)
                            .applicationProtocolConfig(new ApplicationProtocolConfig(
                                    ApplicationProtocolConfig.Protocol.ALPN,
                                    ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
                                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
                                    ApplicationProtocolNames.HTTP_2)))
                    .sslContext();
        } catch (SSLException e) {
            throw new IllegalStateException("Failed to configure kafka-producer mTLS", e);
        }
    }

    private static void requireReadable(Path path, String description) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(description + " is not a readable file: " + path);
        }
    }
}
