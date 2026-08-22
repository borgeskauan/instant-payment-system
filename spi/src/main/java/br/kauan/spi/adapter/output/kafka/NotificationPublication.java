package br.kauan.spi.adapter.output.kafka;

public record NotificationPublication(
        String recipientIspb,
        byte[] payload,
        String communicationId
) {

    public static NotificationPublication create(
            String recipientIspb,
            byte[] payload,
            String communicationId
    ) {
        return new NotificationPublication(recipientIspb, payload, communicationId);
    }
}
