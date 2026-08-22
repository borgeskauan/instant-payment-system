package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static final byte[] EXPECTED_SHA_256 = HexFormat.of().parseHex(
            "3a7d0d942c81d34f73ac13714d9fb187e63e33a9ca505481ccc52ff36ca6c20c"
    );

    @Test
    void exposesAStableImmutableBinaryDigestAndNumericVersion() {
        RequestFingerprint fingerprint = RequestFingerprint.calculate(payment());

        assertThat(fingerprint.version()).isEqualTo((short) 1);
        assertThat(fingerprint.bytes()).containsExactly(EXPECTED_SHA_256);
        assertThat(fingerprint).isEqualTo(RequestFingerprint.calculate(payment()));
        assertThat(fingerprint.hashCode()).isEqualTo(RequestFingerprint.calculate(payment()).hashCode());

        byte[] exposed = fingerprint.bytes();
        exposed[0] ^= 0x7f;

        assertThat(fingerprint.bytes()).containsExactly(EXPECTED_SHA_256);
    }

    private PaymentTransactionCommand payment() {
        return PaymentTransactionCommand.builder()
                .paymentId("E2E-FINGERPRINT-BINARY")
                .amountCents(1_000L)
                .currency("BRL")
                .description("test")
                .sender(party("11111111"))
                .receiver(party("22222222"))
                .build();
    }

    private Party party(String bankCode) {
        return Party.builder()
                .name("Name")
                .taxId("123")
                .pixKey("pix-" + bankCode)
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
