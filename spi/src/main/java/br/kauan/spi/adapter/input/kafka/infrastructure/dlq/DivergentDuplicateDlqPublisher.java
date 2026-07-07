package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.DivergentDuplicatePaymentException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Component
public class DivergentDuplicateDlqPublisher {

    private final DeadLetterPublishingRecoverer divergentDuplicateDeadLetterPublishingRecoverer;

    public DivergentDuplicateDlqPublisher(
            @Qualifier("divergentDuplicateDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer divergentDuplicateDeadLetterPublishingRecoverer
    ) {
        this.divergentDuplicateDeadLetterPublishingRecoverer = divergentDuplicateDeadLetterPublishingRecoverer;
    }

    public void publish(
            ConsumerRecord<String, byte[]> record,
            DivergentDuplicatePaymentException exception
    ) {
        divergentDuplicateDeadLetterPublishingRecoverer.accept(record, null, exception);
    }
}
