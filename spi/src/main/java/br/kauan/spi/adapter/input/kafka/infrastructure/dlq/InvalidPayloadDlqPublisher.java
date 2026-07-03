package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.InvalidInboundPayloadException;
import br.kauan.spi.adapter.input.kafka.consumer.KafkaConsumerLogs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Component
public class InvalidPayloadDlqPublisher {

    private final DeadLetterPublishingRecoverer invalidPayloadDeadLetterPublishingRecoverer;

    public InvalidPayloadDlqPublisher(
            @Qualifier("invalidPayloadDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer invalidPayloadDeadLetterPublishingRecoverer
    ) {
        this.invalidPayloadDeadLetterPublishingRecoverer = invalidPayloadDeadLetterPublishingRecoverer;
    }

    public void publish(
            ConsumerRecord<String, byte[]> record,
            InvalidInboundPayloadException exception
    ) {
        KafkaConsumerLogs.invalidInboundPayload(record);
        invalidPayloadDeadLetterPublishingRecoverer.accept(record, null, exception);
    }
}
