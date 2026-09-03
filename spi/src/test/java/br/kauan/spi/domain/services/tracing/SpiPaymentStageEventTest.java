package br.kauan.spi.domain.services.tracing;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SpiPaymentStageEventTest {

    @Test
    void recordsSemanticStagesOnlyForTheDeterministicSample(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("spi-payment-stages.jfr");

        try (Recording recording = new Recording()) {
            recording.enable("br.kauan.spi.PaymentStage");
            recording.start();

            SpiPaymentStageEvent.record("E2E-29", SpiPaymentStage.REQUEST_CONSUMED);
            SpiPaymentStageEvent.record("E2E-29", SpiPaymentStage.REQUEST_SAVED);
            SpiPaymentStageEvent.record("E2E-1", SpiPaymentStage.REQUEST_CONSUMED);

            recording.stop();
            recording.dump(recordingFile);
        }

        var events = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(event -> event.getEventType().getName().equals("br.kauan.spi.PaymentStage"))
                .toList();

        assertThat(events)
                .extracting(
                        event -> event.getString("endToEndId"),
                        event -> event.getString("stage"))
                .containsExactly(
                        tuple("E2E-29", "request_consumed"),
                        tuple("E2E-29", "request_saved")
                );
    }
}
