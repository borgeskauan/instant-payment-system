package br.kauan.spi.application.notification;

public record OutboundNotification(
        String recipientIspb,
        byte[] payload,
        String communicationId
) {

    public static OutboundNotification create(
            String recipientIspb,
            byte[] payload,
            String communicationId
    ) {
        return new OutboundNotification(recipientIspb, payload, communicationId);
    }
}
