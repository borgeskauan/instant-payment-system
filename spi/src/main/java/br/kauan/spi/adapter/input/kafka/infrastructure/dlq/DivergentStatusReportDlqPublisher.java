package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.DivergentStatusReportException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

@Component
public class DivergentStatusReportDlqPublisher {

    private final DeadLetterPublishingRecoverer divergentStatusReportDeadLetterPublishingRecoverer;

    public DivergentStatusReportDlqPublisher(
            @Qualifier("divergentStatusReportDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer divergentStatusReportDeadLetterPublishingRecoverer
    ) {
        this.divergentStatusReportDeadLetterPublishingRecoverer = divergentStatusReportDeadLetterPublishingRecoverer;
    }

    public void publish(
            ConsumerRecord<String, byte[]> record,
            DivergentStatusReportException exception
    ) {
        divergentStatusReportDeadLetterPublishingRecoverer.accept(record, null, exception);
    }
}
