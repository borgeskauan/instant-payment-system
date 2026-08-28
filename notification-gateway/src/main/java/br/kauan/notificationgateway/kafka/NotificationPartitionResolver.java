package br.kauan.notificationgateway.kafka;

import org.apache.kafka.common.utils.Utils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public final class NotificationPartitionResolver {

    public int partition(String recipientIspb) {
        byte[] serializedKey = recipientIspb.getBytes(StandardCharsets.UTF_8);
        return Utils.toPositive(Utils.murmur2(serializedKey)) % NotificationLog.PARTITION_COUNT;
    }
}
