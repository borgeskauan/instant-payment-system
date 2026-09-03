package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.payment.RequestFingerprint;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

@SpringBootTest
@Transactional
class JdbcPaymentTransactionRepositoryIntegrationTest {

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private JdbcPaymentTransactionRepository adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFixtureRows() {
        jdbcTemplate.update(
                "DELETE FROM payment_transaction_entity WHERE payment_id >= 'E2E-IDEMP-' AND payment_id < 'E2E-IDEMP.'"
        );
        jdbcTemplate.update(
                "DELETE FROM participant_balance_entity WHERE bank_code IN ('11111111', '22222222', '33333333')"
        );
        insertFunds("11111111", "1000.00");
        insertFunds("22222222", "1000.00");
        insertFunds("33333333", "1000.00");
    }

    @Test
    void newPaymentReservesFundsAndPersistsTheClosedWaitingState() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-NEW", "11111111", "22222222");

        PaymentTransactionPersistenceResult result = store(payment);

        assertThat(result.acceptanceRequests()).containsExactly(payment);
        assertThat(result.createdPayments()).containsExactly(payment);
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.WAITING_ACCEPTANCE.name());
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
        assertThat(fingerprint(payment.getPaymentId()))
                .containsExactly(RequestFingerprint.calculate(payment).bytes());
        assertThat(fingerprintVersion(payment.getPaymentId())).isEqualTo((short) 1);
    }

    @Test
    void paymentAuthorizationIsClassifiedAtThePersistenceBoundary() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-UNAUTHORIZED-PAYER", "11111111", "22222222");
        AuthenticatedPaymentRequest request = new AuthenticatedPaymentRequest(0, "33333333", payment);

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(request));

        assertThat(result.unauthorizedRequests()).containsExactly(request);
        assertThat(result.createdPayments()).isEmpty();
        assertThat(rowCount(payment.getPaymentId())).isZero();
        assertThat(balance("11111111")).isEqualByComparingTo("1000.00");
    }

    @Test
    void insufficientFundsIsAnInternalRejectionCause() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-NO-FUNDS", "11111111", "22222222");
        insertFunds("11111111", "0.00");

        PaymentTransactionPersistenceResult result = store(payment);

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.rejectedPayments())
                .extracting(
                        rejection -> rejection.payment().paymentId(),
                        PaymentRejection::cause,
                        PaymentRejection::externalReasonCodes
                )
                .containsExactly(tuple(
                        payment.getPaymentId(),
                        PaymentRejectionCause.INSUFFICIENT_FUNDS,
                        List.of()
                ));
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.REJECTED.name());
        assertThat(rejectionCause(payment.getPaymentId())).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(externalReasonCodes(payment.getPaymentId())).isEmpty();
    }

    @Test
    void reservationsUseSourceOrderWithoutPrefixFairness() {
        PaymentTransactionCommand first = payment("E2E-IDEMP-FIRST", 8_000L, "11111111", "22222222");
        PaymentTransactionCommand tooLarge = payment("E2E-IDEMP-TOO-LARGE", 5_000L, "11111111", "22222222");
        PaymentTransactionCommand laterSmall = payment("E2E-IDEMP-LATER-SMALL", 1_000L, "11111111", "22222222");
        insertFunds("11111111", "90.00");

        PaymentTransactionPersistenceResult result = store(first, tooLarge, laterSmall);

        assertThat(result.acceptanceRequests()).containsExactly(first, laterSmall);
        assertThat(result.rejectedPayments())
                .extracting(rejection -> rejection.payment().paymentId())
                .containsExactly(tooLarge.getPaymentId());
        assertThat(balance("11111111")).isEqualByComparingTo("0.00");
    }

    @Test
    void identicalPaymentReplayDoesNotReserveTwice() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-PAYMENT-REPLAY", "11111111", "22222222");
        store(payment);

        PaymentTransactionPersistenceResult replay = store(payment);

        assertThat(replay.acceptanceRequests()).isEmpty();
        assertThat(replay.createdPayments()).isEmpty();
        assertThat(replay.divergentDuplicates()).isEmpty();
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
    }

    @Test
    void identicalPaymentsInOneBatchCreateOneReservationAndOneAcceptanceRequest() {
        PaymentTransactionCommand first = payment("E2E-IDEMP-SAME-BATCH", "11111111", "22222222");
        PaymentTransactionCommand repeated = payment("E2E-IDEMP-SAME-BATCH", "11111111", "22222222");

        PaymentTransactionPersistenceResult result = store(first, repeated);

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.createdPayments()).containsExactly(first);
        assertThat(result.divergentDuplicates()).isEmpty();
        assertThat(rowCount(first.getPaymentId())).isEqualTo(1);
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
    }

    @Test
    void replayRequiresComparableFingerprintMetadata() {
        PaymentTransactionCommand changedVersion = payment(
                "E2E-IDEMP-FINGERPRINT-VERSION",
                "11111111",
                "22222222"
        );
        PaymentTransactionCommand missingMetadata = payment(
                "E2E-IDEMP-FINGERPRINT-MISSING",
                "11111111",
                "22222222"
        );
        store(changedVersion, missingMetadata);
        jdbcTemplate.update(
                "UPDATE payment_transaction_entity SET request_fingerprint_version = 0 WHERE payment_id = ?",
                changedVersion.getPaymentId()
        );
        jdbcTemplate.update(
                """
                        UPDATE payment_transaction_entity
                        SET request_fingerprint = NULL,
                            request_fingerprint_version = NULL
                        WHERE payment_id = ?
                        """,
                missingMetadata.getPaymentId()
        );

        PaymentTransactionPersistenceResult result = store(changedVersion, missingMetadata);

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates())
                .extracting(AuthenticatedPaymentRequest::command)
                .containsExactly(changedVersion, missingMetadata);
        assertThat(balance("11111111")).isEqualByComparingTo("980.00");
    }

    @Test
    void divergentSameBatchPaymentIdIsRejectedBeforeInsertion() {
        PaymentTransactionCommand first = payment("E2E-IDEMP-DIVERGENT", "11111111", "22222222");
        PaymentTransactionCommand second = payment("E2E-IDEMP-DIVERGENT", "11111111", "33333333");

        PaymentTransactionPersistenceResult result = store(first, second);

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates())
                .extracting(AuthenticatedPaymentRequest::command)
                .containsExactly(first, second);
        assertThat(rowCount(first.getPaymentId())).isZero();
    }

    @Test
    void acceptedOutcomeSettlesAndPreservesExternalReasonCodes() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-ACCEPTED", "11111111", "22222222");
        store(payment);

        StatusReportPersistenceResult result = apply(report(
                payment.getPaymentId(),
                StatusReportOutcome.ACCEPTED,
                "ac01",
                "AB03",
                "AC01"
        ));

        assertThat(result.settlements())
                .extracting(settlement -> settlement.payment().paymentId())
                .containsExactly(payment.getPaymentId());
        assertThat(result.settlements().getFirst().reasonCodes())
                .extracting(StatusReasonCode::value)
                .containsExactly("AB03", "AC01");
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.SETTLED.name());
        assertThat(externalReasonCodes(payment.getPaymentId())).containsExactly("AB03", "AC01");
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
        assertThat(balance("22222222")).isEqualByComparingTo("1010.00");
    }

    @Test
    void identicalAcceptedReportsInOneBatchSettleExactlyOnce() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-ACCEPTED-BATCH-REPLAY", "11111111", "22222222");
        store(payment);
        IncomingStatusReportCommand report = report(
                payment.getPaymentId(),
                StatusReportOutcome.ACCEPTED,
                "AC01"
        );

        StatusReportPersistenceResult result = apply(report, report);

        assertThat(result.settlements())
                .extracting(settlement -> settlement.payment().paymentId())
                .containsExactly(payment.getPaymentId());
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
        assertThat(balance("22222222")).isEqualByComparingTo("1010.00");
    }

    @Test
    void acceptedOutcomeRollsBackWhenReceiverBalanceIsMissing() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-MISSING-RECEIVER", "11111111", "22222222");
        store(payment);
        jdbcTemplate.update("DELETE FROM participant_balance_entity WHERE bank_code = ?", "22222222");

        assertThatThrownBy(() -> apply(report(payment.getPaymentId(), StatusReportOutcome.ACCEPTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("participant balance");

        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.WAITING_ACCEPTANCE.name());
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
    }

    @Test
    void receiverRejectionReleasesPayerAndPersistsExternalCodesExactlyOnce() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-REJECTED", "11111111", "22222222");
        store(payment);

        IncomingStatusReportCommand report = report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "am04",
                "AB03",
                "AM04"
        );
        StatusReportPersistenceResult first = apply(report);
        StatusReportPersistenceResult replay = apply(report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "AB03",
                "AM04"
        ));

        assertThat(first.rejectedPayments())
                .extracting(rejection -> rejection.payment().paymentId())
                .containsExactly(payment.getPaymentId());
        assertThat(first.rejectedPayments().getFirst().externalReasonCodes())
                .extracting(StatusReasonCode::value)
                .containsExactly("AB03", "AM04");
        assertThat(replay.rejectedPayments()).isEmpty();
        assertThat(replay.divergentStatusReports()).isEmpty();
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.REJECTED.name());
        assertThat(rejectionCause(payment.getPaymentId())).isNull();
        assertThat(externalReasonCodes(payment.getPaymentId())).containsExactly("AB03", "AM04");
        assertThat(balance("11111111")).isEqualByComparingTo("1000.00");
        assertThat(balance("22222222")).isEqualByComparingTo("1000.00");
    }

    @Test
    void terminalReplayWithChangedReasonCodesIsDivergent() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-CHANGED-CODE", "11111111", "22222222");
        store(payment);
        apply(report(payment.getPaymentId(), StatusReportOutcome.REJECTED, "AB03"));

        IncomingStatusReportCommand changed = report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "AM04"
        );
        StatusReportPersistenceResult result = apply(changed);

        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(changed);
        assertThat(externalReasonCodes(payment.getPaymentId())).containsExactly("AB03");
        assertThat(balance("11111111")).isEqualByComparingTo("1000.00");
    }

    @Test
    void terminalReplayWithChangedOutcomeIsDivergent() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-CHANGED-OUTCOME", "11111111", "22222222");
        store(payment);
        apply(report(payment.getPaymentId(), StatusReportOutcome.ACCEPTED));

        IncomingStatusReportCommand rejected = report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "AB03"
        );
        StatusReportPersistenceResult result = apply(rejected);

        assertThat(result.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(rejected);
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.SETTLED.name());
    }

    @Test
    void acceptedReplayUsesPersistedReasonIdentity() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-ACCEPTED-REPLAY", "11111111", "22222222");
        store(payment);
        apply(report(payment.getPaymentId(), StatusReportOutcome.ACCEPTED, "AC01"));

        StatusReportPersistenceResult identical = apply(report(
                payment.getPaymentId(),
                StatusReportOutcome.ACCEPTED,
                "ac01"
        ));
        IncomingStatusReportCommand changed = report(
                payment.getPaymentId(),
                StatusReportOutcome.ACCEPTED,
                "AB03"
        );
        StatusReportPersistenceResult divergent = apply(changed);

        assertThat(identical.settlements()).isEmpty();
        assertThat(identical.divergentStatusReports()).isEmpty();
        assertThat(divergent.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(changed);
        assertThat(balance("22222222")).isEqualByComparingTo("1010.00");
    }

    @Test
    void pacs002AfterInternalInsufficientFundsRejectionIsDivergent() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-INTERNAL-REJECT", "11111111", "22222222");
        insertFunds("11111111", "0.00");
        store(payment);
        IncomingStatusReportCommand incoming = report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "AM04"
        );

        StatusReportPersistenceResult result = apply(incoming);

        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(incoming);
        assertThat(rejectionCause(payment.getPaymentId())).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void conflictingReportsInOneBatchDoNotTransitionThePayment() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-CONFLICTING-STATUS", "11111111", "22222222");
        store(payment);
        IncomingStatusReportCommand accepted = report(payment.getPaymentId(), StatusReportOutcome.ACCEPTED);
        IncomingStatusReportCommand rejected = report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "AB03"
        );

        StatusReportPersistenceResult result = apply(accepted, rejected);

        assertThat(result.settlements()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(accepted, rejected);
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.WAITING_ACCEPTANCE.name());
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
    }

    @Test
    void missingAndUnauthorizedStatusReportsDoNotMutateState() {
        IncomingStatusReportCommand missing = report(
                "E2E-IDEMP-MISSING",
                StatusReportOutcome.REJECTED,
                "AB03"
        );
        StatusReportPersistenceResult missingResult = apply(missing);
        assertThat(missingResult.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(missing);

        PaymentTransactionCommand payment = payment("E2E-IDEMP-UNAUTHORIZED", "11111111", "22222222");
        store(payment);
        IncomingStatusReportCommand accepted = report(payment.getPaymentId(), StatusReportOutcome.ACCEPTED);
        StatusReportPersistenceResult unauthorized = adapter.classifyAndApplyIncomingStatusReports(List.of(
                new AuthenticatedStatusReport(0, "33333333", accepted)
        ));

        assertThat(unauthorized.unauthorizedStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(accepted);
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.WAITING_ACCEPTANCE.name());
        assertThat(balance("11111111")).isEqualByComparingTo("990.00");
    }

    @Test
    void authorizedStatusProgressesAlongsideAnUnauthorizedConflictingReport() {
        PaymentTransactionCommand payment = payment("E2E-IDEMP-MIXED-AUTH-STATUS", "11111111", "22222222");
        store(payment);
        IncomingStatusReportCommand accepted = report(payment.getPaymentId(), StatusReportOutcome.ACCEPTED);
        IncomingStatusReportCommand unauthorizedRejection = report(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED,
                "AB03"
        );

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                new AuthenticatedStatusReport(0, "22222222", accepted),
                new AuthenticatedStatusReport(1, "33333333", unauthorizedRejection)
        ));

        assertThat(result.settlements())
                .extracting(settlement -> settlement.payment().paymentId())
                .containsExactly(payment.getPaymentId());
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(result.unauthorizedStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(unauthorizedRejection);
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.SETTLED.name());
        assertThat(balance("22222222")).isEqualByComparingTo("1010.00");
    }

    private PaymentTransactionPersistenceResult store(PaymentTransactionCommand... payments) {
        List<AuthenticatedPaymentRequest> authenticatedPayments = new ArrayList<>(payments.length);
        for (int ordinal = 0; ordinal < payments.length; ordinal++) {
            PaymentTransactionCommand payment = payments[ordinal];
            authenticatedPayments.add(new AuthenticatedPaymentRequest(
                    ordinal,
                    payment.getSender().getAccount().getBankCode(),
                    payment
            ));
        }
        return adapter.storeAndClassifyIncomingPaymentRequests(authenticatedPayments);
    }

    private StatusReportPersistenceResult apply(IncomingStatusReportCommand... reports) {
        List<AuthenticatedStatusReport> authenticatedReports = new ArrayList<>(reports.length);
        for (int ordinal = 0; ordinal < reports.length; ordinal++) {
            authenticatedReports.add(new AuthenticatedStatusReport(ordinal, "22222222", reports[ordinal]));
        }
        return adapter.classifyAndApplyIncomingStatusReports(authenticatedReports);
    }

    private IncomingStatusReportCommand report(
            String paymentId,
            StatusReportOutcome outcome,
            String... reasonCodes
    ) {
        return new IncomingStatusReportCommand(
                paymentId,
                outcome,
                java.util.Arrays.stream(reasonCodes).map(StatusReasonCode::of).toList()
        );
    }

    private void insertFunds(String bankCode, String balance) {
        jdbcTemplate.update(
                """
                        INSERT INTO participant_balance_entity (bank_code, balance_cents)
                        VALUES (?, ?)
                        ON CONFLICT (bank_code) DO UPDATE
                        SET balance_cents = EXCLUDED.balance_cents
                        """,
                bankCode,
                Money.toCents(new BigDecimal(balance))
        );
    }

    private BigDecimal balance(String bankCode) {
        Long balanceCents = jdbcTemplate.queryForObject(
                "SELECT balance_cents FROM participant_balance_entity WHERE bank_code = ?",
                Long.class,
                bankCode
        );
        return Money.toDecimal(balanceCents == null ? 0L : balanceCents);
    }

    private String state(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT state::text FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String rejectionCause(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT rejection_cause::text FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private List<String> externalReasonCodes(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT external_reason_codes FROM payment_transaction_entity WHERE payment_id = ?",
                (resultSet, rowNumber) -> {
                    var array = resultSet.getArray(1);
                    return array == null ? List.of() : List.of((String[]) array.getArray());
                },
                paymentId
        );
    }

    private byte[] fingerprint(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_fingerprint FROM payment_transaction_entity WHERE payment_id = ?",
                byte[].class,
                paymentId
        );
    }

    private Short fingerprintVersion(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_fingerprint_version FROM payment_transaction_entity WHERE payment_id = ?",
                Short.class,
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

    private static PaymentTransactionCommand payment(
            String paymentId,
            String senderBankCode,
            String receiverBankCode
    ) {
        return payment(paymentId, 1_000L, senderBankCode, receiverBankCode);
    }

    private static PaymentTransactionCommand payment(
            String paymentId,
            long amountCents,
            String senderBankCode,
            String receiverBankCode
    ) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(amountCents)
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
