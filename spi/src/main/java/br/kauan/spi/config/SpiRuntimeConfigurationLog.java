package br.kauan.spi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpiRuntimeConfigurationLog implements ApplicationRunner {

    private final KafkaProperties springKafka;
    private final SpiKafkaProperties spiKafka;
    private final NotificationOutboxProperties outbox;

    public SpiRuntimeConfigurationLog(
            KafkaProperties springKafka,
            SpiKafkaProperties spiKafka,
            NotificationOutboxProperties outbox
    ) {
        this.springKafka = springKafka;
        this.spiKafka = spiKafka;
        this.outbox = outbox;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info(summary());
    }

    String summary() {
        return "event=spi_runtime_configuration"
                + " payment_request_listener_concurrency=" + spiKafka.paymentRequestListenerConcurrency()
                + " status_report_listener_concurrency=" + spiKafka.statusReportListenerConcurrency()
                + " payment_request_group_id=" + spiKafka.paymentRequestGroupId()
                + " status_report_group_id=" + spiKafka.statusReportGroupId()
                + batchSummary("payment_request", spiKafka.paymentRequest())
                + batchSummary("status_report", spiKafka.statusReport())
                + " notification_outbox_queue_capacity=" + outbox.queueCapacity()
                + " notification_outbox_recovery_batch_size=" + outbox.recoveryBatchSize()
                + " notification_outbox_retry_delay_ms=" + outbox.retryDelay().toMillis()
                + " kafka_listener_auto_startup=" + springKafka.getListener().isAutoStartup();
    }

    private String batchSummary(String name, SpiKafkaProperties.ConsumerBatch batch) {
        return " " + name + "_max_poll_records=" + batch.maxPollRecords()
                + " " + name + "_fetch_min_bytes=" + batch.fetchMinBytes()
                + " " + name + "_fetch_max_wait_ms=" + batch.fetchMaxWaitMs();
    }
}
