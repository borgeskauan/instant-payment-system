package br.kauan.spi.adapter.output.audit;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.services.audit.PaymentAuditEvent;
import br.kauan.spi.domain.services.audit.PaymentAuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentAuditRepositoryIntegrationTest {

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private PaymentAuditRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsBusinessFactsWithSeparatedStateAndReasonOrigins() {
        repository.insertAll(List.of(
                reservation("E2E-AUDIT-RESERVED"),
                settlement("E2E-AUDIT-SETTLED", List.of(StatusReasonCode.of("AC01"))),
                ingressRejection("E2E-AUDIT-INGRESS-REJECTED"),
                releasedRejection("E2E-AUDIT-RECEIVER-REJECTED", List.of(StatusReasonCode.of("AB03")))
        ));

        List<AuditRow> rows = jdbcTemplate.query(
                """
                        SELECT payment_id,
                               previous_state::text,
                               resulting_state::text,
                               rejection_cause::text,
                               external_reason_codes
                        FROM payment_audit_event
                        WHERE payment_id LIKE 'E2E-AUDIT-%'
                        ORDER BY payment_id
                        """,
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getArray(5) == null
                                ? List.of()
                                : List.of((String[]) resultSet.getArray(5).getArray())
                )
        );

        assertThat(rows).containsExactly(
                new AuditRow(
                        "E2E-AUDIT-INGRESS-REJECTED",
                        null,
                        "REJECTED",
                        "INSUFFICIENT_FUNDS",
                        List.of()
                ),
                new AuditRow(
                        "E2E-AUDIT-RECEIVER-REJECTED",
                        "WAITING_ACCEPTANCE",
                        "REJECTED",
                        null,
                        List.of("AB03")
                ),
                new AuditRow(
                        "E2E-AUDIT-RESERVED",
                        null,
                        "WAITING_ACCEPTANCE",
                        null,
                        List.of()
                ),
                new AuditRow(
                        "E2E-AUDIT-SETTLED",
                        "WAITING_ACCEPTANCE",
                        "SETTLED",
                        null,
                        List.of("AC01")
                )
        );
    }

    @Test
    void databaseRejectsAnInternalCauseOnASettlement() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO payment_audit_event (
                            payment_id, event_type, previous_state, resulting_state,
                            amount_cents, sender_ispb, receiver_ispb,
                            receiver_delta_cents, rejection_cause
                        ) VALUES (
                            ?, 'PAYMENT_SETTLED', 'WAITING_ACCEPTANCE', 'SETTLED',
                            1000, '11111111', '22222222', 1000, 'INSUFFICIENT_FUNDS'
                        )
                        """,
                "E2E-AUDIT-INVALID-CAUSE"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsAReceiverRejectionWithoutExternalReasonCodes() {
        assertThatThrownBy(() -> repository.insertAll(List.of(new PaymentAuditEvent(
                "E2E-AUDIT-MISSING-EXTERNAL-CODE",
                PaymentAuditEventType.PAYMENT_REJECTED,
                PaymentState.WAITING_ACCEPTANCE,
                PaymentState.REJECTED,
                1_000L,
                "11111111",
                "22222222",
                1_000L,
                null,
                null,
                List.of()
        )))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsConflictingTerminalOutcomes() {
        String paymentId = "E2E-AUDIT-CONFLICTING-OUTCOME";

        assertThatThrownBy(() -> repository.insertAll(List.of(
                settlement(paymentId, List.of()),
                releasedRejection(paymentId, List.of(StatusReasonCode.of("AB03")))
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateAdmissionFacts() {
        PaymentAuditEvent reservation = reservation("E2E-AUDIT-DUPLICATE-ADMISSION");

        assertThatThrownBy(() -> repository.insertAll(List.of(reservation, reservation)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsFactsThatDoNotMatchTheirFinancialShape() {
        PaymentAuditEvent invalidReservation = new PaymentAuditEvent(
                "E2E-AUDIT-INVALID-SHAPE",
                PaymentAuditEventType.PAYMENT_RESERVED,
                null,
                PaymentState.WAITING_ACCEPTANCE,
                1_000L,
                "11111111",
                "22222222",
                -999L,
                null,
                null,
                List.of()
        );

        assertThatThrownBy(() -> repository.insertAll(List.of(invalidReservation)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PaymentAuditEvent reservation(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_RESERVED,
                null,
                PaymentState.WAITING_ACCEPTANCE,
                1_000L,
                "11111111",
                "22222222",
                -1_000L,
                null,
                null,
                List.of()
        );
    }

    private PaymentAuditEvent ingressRejection(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_REJECTED,
                null,
                PaymentState.REJECTED,
                1_000L,
                "11111111",
                "22222222",
                null,
                null,
                PaymentRejectionCause.INSUFFICIENT_FUNDS,
                List.of()
        );
    }

    private PaymentAuditEvent releasedRejection(String paymentId, List<StatusReasonCode> reasonCodes) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_REJECTED,
                PaymentState.WAITING_ACCEPTANCE,
                PaymentState.REJECTED,
                1_000L,
                "11111111",
                "22222222",
                1_000L,
                null,
                null,
                reasonCodes
        );
    }

    private PaymentAuditEvent settlement(String paymentId, List<StatusReasonCode> reasonCodes) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_SETTLED,
                PaymentState.WAITING_ACCEPTANCE,
                PaymentState.SETTLED,
                1_000L,
                "11111111",
                "22222222",
                null,
                1_000L,
                null,
                reasonCodes
        );
    }

    private record AuditRow(
            String paymentId,
            String previousState,
            String resultingState,
            String rejectionCause,
            List<String> externalReasonCodes
    ) {
    }
}
