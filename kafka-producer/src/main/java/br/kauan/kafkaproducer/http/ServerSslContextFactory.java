package br.kauan.kafkaproducer.http;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

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
            return SslContextBuilder
                    .forServer(certificateChain.toFile(), privateKey.toFile())
                    .trustManager(trustCertCollection.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
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
