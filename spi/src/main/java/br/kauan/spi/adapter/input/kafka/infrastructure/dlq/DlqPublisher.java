package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.InvalidInboundPayloadException;
import br.kauan.spi.adapter.input.kafka.consumer.KafkaConsumerLogs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Component
public class DlqPublisher {

    private final DeadLetterPublishingRecoverer recoverer;

    public DlqPublisher(DeadLetterPublishingRecoverer recoverer) {
        this.recoverer = recoverer;
    }

    public void publish(ConsumerRecord<String, byte[]> record, Exception exception) {
        if (exception instanceof InvalidInboundPayloadException) {
            KafkaConsumerLogs.invalidInboundPayload(record);
        }
        recoverer.accept(record, null, exception);
    }
}
