package br.kauan.spi.adapter.output.audit;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
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
    void databaseAllowsInsufficientFundsReasonOnARejectedStatusChange() {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_audit_event (
                            payment_id,
                            event_type,
                            previous_status,
                            resulting_status,
                            reason
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                "E2E-AUDIT-REPOSITORY-REJECTION-REASON",
                PaymentAuditEventType.PAYMENT_STATUS_CHANGED.name(),
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                PaymentStatus.REJECTED.name(),
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
                            reason
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                "E2E-AUDIT-REPOSITORY-INVALID-REJECTION-REASON",
                PaymentAuditEventType.PAYMENT_STATUS_CHANGED.name(),
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                PaymentStatus.ACCEPTED_IN_PROCESS.name(),
                "INSUFFICIENT_FUNDS"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertsMixedEventsInOneBulkWithDatabaseTimestamps() {
        repository.insertAll(List.of(
                creation("E2E-AUDIT-REPOSITORY-CREATED"),
                statusChange(
                        "E2E-AUDIT-REPOSITORY-SETTLED",
                        PaymentStatus.ACCEPTED_AND_SETTLED
                ),
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

        assertThat(rows).hasSize(3).allSatisfy(row -> assertThat(row.occurredAt()).isNotNull());
        assertThat(rows).extracting(AuditRow::eventType)
                .containsExactlyInAnyOrder(
                        "PAYMENT_CREATED",
                        "PAYMENT_STATUS_CHANGED",
                        "SETTLEMENT_APPLIED"
                );
        assertThat(rows).filteredOn(row -> row.eventType().equals("SETTLEMENT_APPLIED"))
                .containsExactly(new AuditRow(
                        "E2E-AUDIT-REPOSITORY-SETTLED",
                        "SETTLEMENT_APPLIED",
                        null,
                        null,
                        1_000L,
                        "11111111",
                        "22222222",
                        -1_000L,
                        1_000L,
                        rows.stream()
                                .filter(row -> row.eventType().equals("SETTLEMENT_APPLIED"))
                                .findFirst()
                                .orElseThrow()
                                .occurredAt()
                ));
    }

    @Test
    void allowsTheSameStatusTransitionMoreThanOnce() {
        PaymentAuditEvent transition = statusChange(
                "E2E-AUDIT-REPOSITORY-REPEATED-TRANSITION",
                PaymentStatus.REJECTED
        );

        repository.insertAll(List.of(transition, transition));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_audit_event WHERE payment_id = ?",
                Integer.class,
                transition.paymentId()
        )).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateCreationEvents() {
        PaymentAuditEvent creation = creation("E2E-AUDIT-REPOSITORY-DUPLICATE-CREATION");

        assertThatThrownBy(() -> repository.insertAll(List.of(creation, creation)))
                .isInstanceOf(DataIntegrityViolationException.class);
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
                PaymentAuditEventType.SETTLEMENT_APPLIED,
                null,
                null,
                2_000L,
                "11111111",
                "22222222",
                -2_000L,
                2_000L
        );

        assertThatThrownBy(() -> repository.insertAll(List.of(first, divergent)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEventsThatDoNotMatchTheirRequiredShape() {
        PaymentAuditEvent invalidStatusChange = new PaymentAuditEvent(
                "E2E-AUDIT-REPOSITORY-INVALID-SHAPE",
                PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.REJECTED,
                1_000L,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> repository.insertAll(List.of(invalidStatusChange)))
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
                        ) VALUES (?, ?, NULL, ?, ?, ?, ?, NULL, NULL)
                        """,
                "E2E-AUDIT-REPOSITORY-INVALID-TYPE",
                "UNKNOWN_EVENT",
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                1_000L,
                "11111111",
                "22222222"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private PaymentAuditEvent creation(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_CREATED,
                null,
                PaymentStatus.WAITING_ACCEPTANCE,
                1_000L,
                "11111111",
                "22222222",
                null,
                null
        );
    }

    private PaymentAuditEvent statusChange(String paymentId, PaymentStatus resultingStatus) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
                PaymentStatus.WAITING_ACCEPTANCE,
                resultingStatus,
                null,
                null,
                null,
                null,
                null
        );
    }

    private PaymentAuditEvent settlement(String paymentId) {
        return new PaymentAuditEvent(
                paymentId,
                PaymentAuditEventType.SETTLEMENT_APPLIED,
                null,
                null,
                1_000L,
                "11111111",
                "22222222",
                -1_000L,
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
