package br.kauan.spi.adapter.input.kafka.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;

@Slf4j
public final class KafkaConsumerLogs {

    private KafkaConsumerLogs() {
    }

    public static void invalidInboundPayload(ConsumerRecord<String, byte[]> record) {
        log.warn("event=spi_invalid_inbound_payload topic={} partition={} offset={}",
                record.topic(), record.partition(), record.offset());
    }

    public static void infrastructureUnavailable(String topic, int records, RuntimeException exception) {
        log.error("event=spi_infrastructure_unavailable resource=database topic={} records={} exception_class={} error_message={}",
                topic, records, exception.getClass().getName(), exception.getMessage());
    }
}
