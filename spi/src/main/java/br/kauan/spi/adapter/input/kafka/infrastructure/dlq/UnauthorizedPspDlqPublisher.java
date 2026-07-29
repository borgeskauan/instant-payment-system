package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.UnauthorizedPspException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Component
public class UnauthorizedPspDlqPublisher {

    private final DeadLetterPublishingRecoverer unauthorizedPspDeadLetterPublishingRecoverer;

    public UnauthorizedPspDlqPublisher(
            @Qualifier("unauthorizedPspDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer unauthorizedPspDeadLetterPublishingRecoverer
    ) {
        this.unauthorizedPspDeadLetterPublishingRecoverer = unauthorizedPspDeadLetterPublishingRecoverer;
    }

    public void publish(
            ConsumerRecord<String, byte[]> record,
            UnauthorizedPspException exception
    ) {
        unauthorizedPspDeadLetterPublishingRecoverer.accept(record, null, exception);
    }
}
