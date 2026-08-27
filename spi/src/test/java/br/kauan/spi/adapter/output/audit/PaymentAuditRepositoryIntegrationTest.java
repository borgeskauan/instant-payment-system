package br.kauan.spi.adapter.output.audit;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.services.audit.PaymentAuditEvent;
import br.kauan.spi.domain.services.audit.PaymentAuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
    void databasePersistsTheReservedBusinessFact() {
        PaymentAuditEvent reservation = new PaymentAuditEvent(
                "E2E-AUDIT-REPOSITORY-RESERVED",
                PaymentAuditEventType.PAYMENT_RESERVED,
                null,
                PaymentStatus.WAITING_ACCEPTANCE,
                1_000L,
                "11111111",
                "22222222",
                -1_000L,
                null
        );

        repository.insertAll(List.of(reservation));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type::text FROM payment_audit_event WHERE payment_id = ?",
                String.class,
                reservation.paymentId()
        )).isEqualTo("PAYMENT_RESERVED");
    }

    @Test
    void databaseAllowsInsufficientFundsReasonOnAnIngressRejection() {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_audit_event (
                            payment_id,
                            event_type,
                            previous_status,
                            resulting_status,
                            amount_cents,
                            sender_ispb,
                            receiver_ispb,
                            reason
                        ) VALUES (
                            ?,
                            ?::payment_audit_event_type,
                            NULL,
                            ?::payment_status,
                            ?,
                            ?,
                            ?,
                            ?::payment_rejection_reason
                        )
                        """,
                "E2E-AUDIT-REPOSITORY-REJECTION-REASON",
                PaymentAuditEventType.PAYMENT_REJECTED.name(),
                PaymentStatus.REJECTED.name(),
                1_000L,
                "11111111",
                "22222222",
                "INSUFFICIENT_FUNDS"
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM payment_audit_event WHERE payment_id = ?",
                String.class,
                "E2E-AUDIT-REPOSITORY-REJECTION-REASON"
        )).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void databaseRejectsAReasonOnANonRejectedAuditEvent() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO payment_audit_event (
                            payment_id,
                            event_type,
                            previous_status,
                            resulting_status,
                            amount_cents,
                            sender_ispb,
                            receiver_ispb,
                            receiver_delta_cents,
                            reason
                        ) VALUES (
                            ?,
                            ?::payment_audit_event_type,
                            ?::payment_status,
                            ?::payment_status,
                            ?,
                            ?,
                            ?,
                            ?,
                            ?::payment_rejection_reason
                        )
                        """,
                "E2E-AUDIT-REPOSITORY-INVALID-REJECTION-REASON",
                PaymentAuditEventType.PAYMENT_SETTLED.name(),
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                PaymentStatus.ACCEPTED_AND_SETTLED.name(),
                1_000L,
                "11111111",
                "22222222",
                1_000L,
                "INSUFFICIENT_FUNDS"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertsReservedAndSettledFactsInOneBulkWithDatabaseTimestamps() {
        repository.insertAll(List.of(
                reservation("E2E-AUDIT-REPOSITORY-RESERVED-BULK"),
                settlement("E2E-AUDIT-REPOSITORY-SETTLED")
        ));

        List<AuditRow> rows = jdbcTemplate.query(
                """
                        SELECT
                            payment_id,
                            event_type,
                            previous_status,
                            resulting_status,
                            amount_cents,
                            sender_ispb,
                            receiver_ispb,
                            sender_delta_cents,
                            receiver_delta_cents,
                            occurred_at
                        FROM payment_audit_event
                        WHERE payment_id LIKE 'E2E-AUDIT-REPOSITORY-%'
                        """,
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getString("payment_id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("previous_status"),
                        resultSet.getString("resulting_status"),
                        resultSet.getObject("amount_cents", Long.class),
                        resultSet.getString("sender_ispb"),
                        resultSet.getString("receiver_ispb"),
                        resultSet.getObject("sender_delta_cents", Long.class),
                        resultSet.getObject("receiver_delta_cents", Long.class),
                        resultSet.getObject("occurred_at", OffsetDateTime.class)
                )
        );

        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row.occurredAt()).isNotNull());
        assertThat(rows).extracting(AuditRow::eventType)
                .containsExactlyInAnyOrder(
                        "PAYMENT_RESERVED",
                        "PAYMENT_SETTLED"
                );
        assertThat(rows).filteredOn(row -> row.eventType().equals("PAYMENT_SETTLED"))
                .containsExactly(new AuditRow(
                        "E2E-AUDIT-REPOSITORY-SETTLED",
                        "PAYMENT_SETTLED",
                        "WAITING_ACCEPTANCE",
                        "ACCEPTED_AND_SETTLED",
                        1_000L,
                        "11111111",
                        "22222222",
                        null,
                        1_000L,
                        rows.stream()
                                .filter(row -> row.eventType().equals("PAYMENT_SETTLED"))
                                .findFirst()
                                .orElseThrow()
                                .occurredAt()
                ));
    }

    @Test
    void rejectsConflictingTerminalOutcomes() {
        String paymentId = "E2E-AUDIT-REPOSITORY-CONFLICTING-OUTCOME";

        assertThatThrownBy(() -> repository.insertAll(List.of(
                settlement(paymentId),
                releasedRejection(paymentId)
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateReservationEvents() {
        PaymentAuditEvent reservation = reservation("E2E-AUDIT-REPOSITORY-DUPLICATE-RESERVATION");

        assertThatThrownBy(() -> repository.insertAll(List.of(reservation, reservation)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsConflictingAdmissionOutcomes() {
        String paymentId = "E2E-AUDIT-REPOSITORY-CONFLICTING-ADMISSION";

        assertThatThrownBy(() -> repository.insertAll(List.of(
                reservation(paymentId),
                ingressRejection(paymentId)
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateSettlementEvents() {
        PaymentAuditEvent settlement = settlement("E2E-AUDIT-REPOSITORY-DUPLICATE-SETTLEMENT");

        assertThatThrownBy(() -> repository.insertAll(List.of(settlement, settlement)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDivergentSettlementEventsForTheSamePayment() {
        String paymentId = "E2E-AUDIT-REPOSITORY-DIVERGENT-SETTLEMENT";
        PaymentAuditEvent first = settlement(paymentId);
        PaymentAuditEvent divergent = new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_SETTLED,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED,
                2_000L,
                "11111111",
                "22222222",
                null,
                2_000L
        );

        assertThatThrownBy(() -> repository.insertAll(List.of(first, divergent)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEventsThatDoNotMatchTheirRequiredShape() {
        PaymentAuditEvent invalidReservation = new PaymentAuditEvent(
                "E2E-AUDIT-REPOSITORY-INVALID-SHAPE",
                PaymentAuditEventType.PAYMENT_RESERVED,
                null,
                PaymentStatus.WAITING_ACCEPTANCE,
                1_000L,
                "11111111",
                "22222222",
                -999L,
                null
        );

        assertThatThrownBy(() -> repository.insertAll(List.of(invalidReservation)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownEventTypes() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO payment_audit_event (
                            payment_id,
                            event_type,
                            previous_status,
                            resulting_status,
                            amount_cents,
                            sender_ispb,
                            receiver_ispb,
                            sender_delta_cents,
                            receiver_delta_cents
                        ) VALUES (
                            ?,
                            ?::payment_audit_event_type,
                            NULL,
                            ?::payment_status,
                            ?,
                            ?,
                            ?,
                            NULL,
                            NULL
                        )
                        """,
                "E2E-AUDIT-REPOSITORY-INVALID-TYPE",
                "UNKNOWN_EVENT",
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                1_000L,
                "11111111",
                "22222222"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private PaymentAuditEvent reservation(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_RESERVED,
                null,
                PaymentStatus.WAITING_ACCEPTANCE,
                1_000L,
                "11111111",
                "22222222",
                -1_000L,
                null
        );
    }

    private PaymentAuditEvent ingressRejection(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_REJECTED,
                null,
                PaymentStatus.REJECTED,
                1_000L,
                "11111111",
                "22222222",
                null,
                null,
                PaymentRejectionReason.INSUFFICIENT_FUNDS
        );
    }

    private PaymentAuditEvent releasedRejection(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_REJECTED,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.REJECTED,
                1_000L,
                "11111111",
                "22222222",
                1_000L,
                null
        );
    }

    private PaymentAuditEvent settlement(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_SETTLED,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED,
                1_000L,
                "11111111",
                "22222222",
                null,
                1_000L
        );
    }

    private record AuditRow(
            String paymentId,
            String eventType,
            String previousStatus,
            String resultingStatus,
            Long amountCents,
            String senderIspb,
            String receiverIspb,
            Long senderDeltaCents,
            Long receiverDeltaCents,
            OffsetDateTime occurredAt
    ) {
    }
}
