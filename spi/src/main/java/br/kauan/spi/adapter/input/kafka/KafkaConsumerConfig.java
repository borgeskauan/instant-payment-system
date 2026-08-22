package br.kauan.spi.adapter.input.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    private static final String CLIENT_TELEMETRY_PUSH_ENABLED = "enable.metrics.push";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.auto-offset-reset}")
    private String autoOffsetReset;

    @Value("${spi.kafka.payment-request-listener-concurrency:3}")
    private int paymentRequestListenerConcurrency;

    @Value("${spi.kafka.status-report-listener-concurrency:3}")
    private int statusReportListenerConcurrency;

    @Value("${spi.kafka.payment-request.max-poll-records:500}")
    private int paymentRequestMaxPollRecords;

    @Value("${spi.kafka.payment-request.fetch-min-bytes:131072}")
    private int paymentRequestFetchMinBytes;

    @Value("${spi.kafka.payment-request.fetch-max-wait-ms:100}")
    private int paymentRequestFetchMaxWaitMs;

    @Value("${spi.kafka.status-report.max-poll-records:500}")
    private int statusReportMaxPollRecords;

    @Value("${spi.kafka.status-report.fetch-min-bytes:1024}")
    private int statusReportFetchMinBytes;

    @Value("${spi.kafka.status-report.fetch-max-wait-ms:10}")
    private int statusReportFetchMaxWaitMs;

    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean listenerAutoStartup;

    @Bean
    public ConsumerFactory<String, byte[]> paymentRequestConsumerFactory() {
        return consumerFactory(
                paymentRequestMaxPollRecords,
                paymentRequestFetchMinBytes,
                paymentRequestFetchMaxWaitMs
        );
    }

    @Bean
    public ConsumerFactory<String, byte[]> statusReportConsumerFactory() {
        return consumerFactory(
                statusReportMaxPollRecords,
                statusReportFetchMinBytes,
                statusReportFetchMaxWaitMs
        );
    }

    private ConsumerFactory<String, byte[]> consumerFactory(
            int maxPollRecords,
            int fetchMinBytes,
            int fetchMaxWaitMs
    ) {
        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(CLIENT_TELEMETRY_PUSH_ENABLED, false);

        // Performance tuning
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> paymentRequestKafkaListenerContainerFactory(
            @Qualifier("paymentRequestConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("kafkaErrorHandler") CommonErrorHandler kafkaErrorHandler
    ) {
        return listenerContainerFactory(
                consumerFactory,
                kafkaErrorHandler,
                paymentRequestListenerConcurrency
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> statusReportKafkaListenerContainerFactory(
            @Qualifier("statusReportConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("kafkaErrorHandler") CommonErrorHandler kafkaErrorHandler
    ) {
        return listenerContainerFactory(
                consumerFactory,
                kafkaErrorHandler,
                statusReportListenerConcurrency
        );
    }

    private ConcurrentKafkaListenerContainerFactory<String, byte[]> listenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory,
            CommonErrorHandler kafkaErrorHandler,
            int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setAutoStartup(listenerAutoStartup);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return factory;
    }
}
