package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PaymentTransactionJpaAdapterIntegrationTest {

    @Autowired
    private PaymentTransactionJpaAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM payment_transaction_entity WHERE payment_id LIKE 'E2E-IDEMP-%'");
    }

    @Test
    void newPaymentReturnsAcceptanceRequestAndPersistsFingerprintVersion() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-NEW", "11111111", "22222222");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).containsExactly(payment);
        assertThat(result.divergentDuplicates()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(fingerprint(payment.getPaymentId())).isEqualTo(PaymentTransactionFingerprint.from(payment));
        assertThat(fingerprintVersion(payment.getPaymentId())).isEqualTo("v1");
    }

    @Test
    void repeatedIdenticalNewPaymentReturnsOneAcceptanceRequestForFirstOrdinalOnly() {
        PaymentTransactionCommand first = paymentTransaction("E2E-IDEMP-SAME-BATCH", "11111111", "22222222");
        PaymentTransactionCommand repeated = paymentTransaction("E2E-IDEMP-SAME-BATCH", "11111111", "22222222");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).isEmpty();
        assertThat(rowCount(first.getPaymentId())).isEqualTo(1);
    }

    @Test
    void repeatedIdenticalExistingWaitingPaymentReturnsOneAcceptanceRequestForFirstOrdinalOnly() {
        PaymentTransactionCommand first = paymentTransaction("E2E-IDEMP-EXISTING-WAITING", "11111111", "22222222");
        PaymentTransactionCommand repeated = paymentTransaction("E2E-IDEMP-EXISTING-WAITING", "11111111", "22222222");
        adapter.storeAndClassifyIncomingPaymentRequests(List.of(first));

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).isEmpty();
    }

    @Test
    void identicalExistingAdvancedPaymentIsNoOp() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-ADVANCED", "11111111", "22222222");
        adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));
        jdbcTemplate.update(
                "UPDATE payment_transaction_entity SET status = ? WHERE payment_id = ?",
                PaymentStatus.ACCEPTED_IN_PROCESS.name(),
                payment.getPaymentId()
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).isEmpty();
    }

    @Test
    void existingPaymentWithSameFingerprintButDifferentVersionIsDivergent() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-VERSION", "11111111", "22222222");
        insertPayment(
                payment,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentTransactionFingerprint.from(payment),
                "v0"
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(payment);
    }

    @Test
    void existingLegacyPaymentWithoutComparableFingerprintVersionIsDivergent() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-LEGACY", "11111111", "22222222");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(payment);
    }

    @Test
    void sameBatchDivergentPaymentIdReturnsEveryRecordAsDivergentAndInsertsNone() {
        PaymentTransactionCommand first = paymentTransaction("E2E-IDEMP-DIVERGENT-BATCH", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-IDEMP-DIVERGENT-BATCH", "11111111", "33333333");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(first, second);
        assertThat(rowCount(first.getPaymentId())).isZero();
    }

    private void insertPayment(
            PaymentTransactionCommand payment,
            PaymentStatus status,
            String requestFingerprint,
            String requestFingerprintVersion
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_transaction_entity (
                            payment_id,
                            amount_cents,
                            status,
                            sender_bank_code,
                            receiver_bank_code,
                            request_fingerprint,
                            request_fingerprint_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                payment.getPaymentId(),
                payment.getAmountCents(),
                status.name(),
                payment.getSender().getAccount().getBankCode(),
                payment.getReceiver().getAccount().getBankCode(),
                requestFingerprint,
                requestFingerprintVersion
        );
    }

    private String status(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String fingerprint(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_fingerprint FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String fingerprintVersion(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_fingerprint_version FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private Integer rowCount(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transaction_entity WHERE payment_id = ?",
                Integer.class,
                paymentId
        );
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .currency("BRL")
                .description("test")
                .sender(party(senderBankCode))
                .receiver(party(receiverBankCode))
                .build();
    }

    private static Party party(String bankCode) {
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
