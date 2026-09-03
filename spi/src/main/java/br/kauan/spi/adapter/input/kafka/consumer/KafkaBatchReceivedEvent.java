package br.kauan.spi.adapter.input.kafka.consumer;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("br.kauan.spi.KafkaBatchReceived")
@Label("Kafka batch processing")
@Category({"SPI", "Kafka"})
@Description("Records the size and processing duration of an SPI Kafka batch listener callback")
@Enabled(true)
@StackTrace(false)
final class KafkaBatchReceivedEvent extends Event implements AutoCloseable {

    @Label("Topic")
    public String topic;

    @Label("Record count")
    public int recordCount;

    static KafkaBatchReceivedEvent start(String topic, int recordCount) {
        KafkaBatchReceivedEvent event = new KafkaBatchReceivedEvent();
        event.topic = topic;
        event.recordCount = recordCount;
        event.begin();
        return event;
    }

    @Override
    public void close() {
        commit();
    }
}
