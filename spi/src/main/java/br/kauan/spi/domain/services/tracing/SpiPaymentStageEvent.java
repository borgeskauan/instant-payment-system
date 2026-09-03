package br.kauan.spi.domain.services.tracing;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("br.kauan.spi.PaymentStage")
@Label("SPI payment stage")
@Category({"SPI", "Payment"})
@Description("Records sampled semantic checkpoints while an SPI payment is processed")
@Enabled(true)
@StackTrace(false)
public final class SpiPaymentStageEvent extends Event {

    private static final int SAMPLE_RATE = 100;

    @Label("End-to-end identifier")
    public String endToEndId;

    @Label("Stage")
    public String stage;

    public static void record(String endToEndId, SpiPaymentStage stage) {
        if (endToEndId == null || stage == null || !isSampled(endToEndId)) {
            return;
        }

        SpiPaymentStageEvent event = new SpiPaymentStageEvent();
        event.endToEndId = endToEndId;
        event.stage = stage.eventName();
        event.commit();
    }

    private static boolean isSampled(String endToEndId) {
        return Math.floorMod(endToEndId.hashCode(), SAMPLE_RATE) == 0;
    }
}
