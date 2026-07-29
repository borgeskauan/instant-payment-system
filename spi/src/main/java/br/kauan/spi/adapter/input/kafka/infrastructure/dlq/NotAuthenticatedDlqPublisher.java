package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.NotAuthenticatedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Component
public class NotAuthenticatedDlqPublisher {

    private final DeadLetterPublishingRecoverer notAuthenticatedDeadLetterPublishingRecoverer;

    public NotAuthenticatedDlqPublisher(
            @Qualifier("notAuthenticatedDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer notAuthenticatedDeadLetterPublishingRecoverer
    ) {
        this.notAuthenticatedDeadLetterPublishingRecoverer = notAuthenticatedDeadLetterPublishingRecoverer;
    }

    public void publish(
            ConsumerRecord<String, byte[]> record,
            NotAuthenticatedException exception
    ) {
        notAuthenticatedDeadLetterPublishingRecoverer.accept(record, null, exception);
    }
}
