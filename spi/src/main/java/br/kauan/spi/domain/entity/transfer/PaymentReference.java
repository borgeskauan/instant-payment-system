package br.kauan.spi.domain.entity.transfer;

import java.util.Objects;

public record PaymentReference(
        String paymentId,
        long amountCents,
        String senderIspb,
        String receiverIspb
) {

    public PaymentReference {
        requireText(paymentId, "Payment ID");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        requireText(senderIspb, "Sender ISPB");
        requireText(receiverIspb, "Receiver ISPB");
    }

    public static PaymentReference from(PaymentTransactionCommand payment) {
        Objects.requireNonNull(payment, "Payment cannot be null");
        return new PaymentReference(
                payment.getPaymentId(),
                payment.getAmountCents(),
                payment.senderIspb(),
                payment.receiverIspb()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank");
        }
    }
}
