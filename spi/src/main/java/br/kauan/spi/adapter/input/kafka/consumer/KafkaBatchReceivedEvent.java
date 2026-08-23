package br.kauan.spi.adapter.input.kafka.consumer;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("br.kauan.spi.KafkaBatchReceived")
@Label("Kafka batch received")
@Category({"SPI", "Kafka"})
@Description("Records the number of Kafka records delivered to an SPI batch listener callback")
@Enabled(true)
@StackTrace(false)
final class KafkaBatchReceivedEvent extends Event {

    @Label("Topic")
    public String topic;

    @Label("Record count")
    public int recordCount;

    static void record(String topic, int recordCount) {
        KafkaBatchReceivedEvent event = new KafkaBatchReceivedEvent();
        event.topic = topic;
        event.recordCount = recordCount;
        event.commit();
    }
}
