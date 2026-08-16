package br.kauan.notificationgateway.delivery;

import java.util.Objects;

public record Acknowledgement(String communicationId, String recipientIspb) {

    public Acknowledgement {
        Objects.requireNonNull(communicationId, "communicationId");
        Objects.requireNonNull(recipientIspb, "recipientIspb");
        if (communicationId.isBlank()) {
            throw new IllegalArgumentException("communicationId must not be blank");
        }
        if (recipientIspb.isBlank()) {
            throw new IllegalArgumentException("recipientIspb must not be blank");
        }
    }
}
