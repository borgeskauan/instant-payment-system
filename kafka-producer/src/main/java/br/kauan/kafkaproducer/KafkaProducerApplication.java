package br.kauan.kafkaproducer;

import br.kauan.kafkaproducer.config.AppConfig;
import br.kauan.kafkaproducer.http.ReactorNettyPaymentServer;
import br.kauan.kafkaproducer.kafka.KafkaPaymentPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;

public class KafkaProducerApplication {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerApplication.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv(System.getenv());
        KafkaPaymentPublisher publisher = KafkaPaymentPublisher.fromConfig(config);
        publisher.warmUp();

        DisposableServer server = new ReactorNettyPaymentServer(config.port(), publisher).start();
        log.info("Kafka producer started: port={} bootstrapServers={} routing=reactor-netty-direct",
                config.port(), config.kafkaBootstrapServers());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.disposeNow();
            publisher.close();
        }, "kafka-producer-shutdown"));

        server.onDispose().block();
    }
}
