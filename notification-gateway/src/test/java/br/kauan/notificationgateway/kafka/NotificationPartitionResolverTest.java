package br.kauan.notificationgateway.kafka;

import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPartitionResolverTest {

    @Test
    void matchesTheKafkaDefaultKeyPartitioningForTheFixedEightPartitions() {
        NotificationPartitionResolver resolver = new NotificationPartitionResolver(8);
        String recipient = "20000001";
        int expected = Utils.toPositive(Utils.murmur2(recipient.getBytes(StandardCharsets.UTF_8))) % 8;

        assertThat(resolver.partition(recipient)).isEqualTo(expected);
        assertThat(resolver.partition(recipient)).isEqualTo(resolver.partition(recipient));
    }
}
