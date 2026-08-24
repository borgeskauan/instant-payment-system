package br.kauan.notificationgateway.kafka;

import org.apache.kafka.common.utils.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public final class NotificationPartitionResolver {

    private final int partitionCount;

    public NotificationPartitionResolver(
            @Value("${notification-gateway.kafka.partition-count:8}") int partitionCount
    ) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("notification partition count must be positive");
        }
        this.partitionCount = partitionCount;
    }

    public int partition(String recipientIspb) {
        byte[] serializedKey = recipientIspb.getBytes(StandardCharsets.UTF_8);
        return Utils.toPositive(Utils.murmur2(serializedKey)) % partitionCount;
    }
}
