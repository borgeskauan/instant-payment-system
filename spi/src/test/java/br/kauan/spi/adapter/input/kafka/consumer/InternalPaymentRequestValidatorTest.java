package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.pix.internal.v1.BankAccount;
import br.kauan.pix.internal.v1.Party;
import br.kauan.pix.internal.v1.PaymentRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalPaymentRequestValidatorTest {

    @Test
    void acceptsTheMinimumSupportedInternalContract() {
        assertThatCode(() -> InternalPaymentRequestValidator.validate(validRequest().build()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrOversizedPaymentIdentity() {
        assertThatThrownBy(() -> InternalPaymentRequestValidator.validate(
                validRequest().setPaymentId(" ").build()))
                .isInstanceOf(InvalidInboundPayloadException.class)
                .hasMessageContaining("Payment ID");

        assertThatThrownBy(() -> InternalPaymentRequestValidator.validate(
                validRequest().setPaymentId("x".repeat(256)).build()))
                .isInstanceOf(InvalidInboundPayloadException.class)
                .hasMessageContaining("Payment ID");
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> InternalPaymentRequestValidator.validate(
                validRequest().setAmountCents(0).build()))
                .isInstanceOf(InvalidInboundPayloadException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> InternalPaymentRequestValidator.validate(
                validRequest().setCurrency("USD").build()))
                .isInstanceOf(InvalidInboundPayloadException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsInvalidParticipantIspb() {
        PaymentRequest request = validRequest()
                .setReceiver(party("not-an-ispb", "CHECKING"))
                .build();

        assertThatThrownBy(() -> InternalPaymentRequestValidator.validate(request))
                .isInstanceOf(InvalidInboundPayloadException.class)
                .hasMessageContaining("receiver ISPB");
    }

    @Test
    void rejectsUnsupportedAccountTypeButAllowsItsAbsence() {
        PaymentRequest unsupported = validRequest()
                .setSender(party("10000001", "CRYPTO"))
                .build();
        PaymentRequest absent = validRequest()
                .setSender(party("10000001", ""))
                .build();

        assertThatThrownBy(() -> InternalPaymentRequestValidator.validate(unsupported))
                .isInstanceOf(InvalidInboundPayloadException.class)
                .hasMessageContaining("account type");
        assertThatCode(() -> InternalPaymentRequestValidator.validate(absent))
                .doesNotThrowAnyException();
    }

    private PaymentRequest.Builder validRequest() {
        return PaymentRequest.newBuilder()
                .setPaymentId("E2E-VALID")
                .setAmountCents(100)
                .setCurrency("BRL")
                .setSender(party("10000001", "CHECKING"))
                .setReceiver(party("20000001", "CHECKING"));
    }

    private Party party(String ispb, String accountType) {
        return Party.newBuilder()
                .setAccount(BankAccount.newBuilder()
                        .setIspb(ispb)
                        .setType(accountType))
                .build();
    }
}
