package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.pix.internal.v1.Party;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.spi.domain.entity.transfer.BankAccountType;

import java.util.regex.Pattern;

final class InternalPaymentRequestValidator {

    private static final int MAX_PAYMENT_ID_LENGTH = 255;
    private static final String SUPPORTED_CURRENCY = "BRL";
    private static final Pattern ISPB_PATTERN = Pattern.compile("\\d{8}");

    private InternalPaymentRequestValidator() {
    }

    static void validate(PaymentRequest request) {
        String paymentId = request.getPaymentId();
        if (paymentId.isBlank() || paymentId.length() > MAX_PAYMENT_ID_LENGTH) {
            throw invalid("Payment ID must contain between 1 and 255 characters");
        }
        if (request.getAmountCents() <= 0) {
            throw invalid("Payment amount must be positive");
        }
        if (!SUPPORTED_CURRENCY.equals(request.getCurrency())) {
            throw invalid("Unsupported payment currency: " + request.getCurrency());
        }

        validateParty(request.getSender(), "sender");
        validateParty(request.getReceiver(), "receiver");
    }

    private static void validateParty(Party party, String role) {
        String ispb = party.getAccount().getIspb();
        if (!ISPB_PATTERN.matcher(ispb).matches()) {
            throw invalid("Payment " + role + " ISPB must contain exactly 8 digits");
        }

        String accountType = party.getAccount().getType();
        if (!accountType.isBlank()) {
            try {
                BankAccountType.fromString(accountType);
            } catch (IllegalArgumentException exception) {
                throw invalid("Unsupported payment " + role + " account type: " + accountType, exception);
            }
        }
    }

    private static InvalidInboundPayloadException invalid(String message) {
        return new InvalidInboundPayloadException(message);
    }

    private static InvalidInboundPayloadException invalid(String message, Throwable cause) {
        return new InvalidInboundPayloadException(message, cause);
    }
}
