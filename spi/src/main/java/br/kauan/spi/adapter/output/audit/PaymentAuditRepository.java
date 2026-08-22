package br.kauan.spi.adapter.output.audit;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.services.audit.PaymentAuditEvent;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;

@Repository
public class PaymentAuditRepository {

    private static final String INSERT_SQL = """
            INSERT INTO payment_audit_event (
                payment_id,
                event_type,
                previous_status,
                resulting_status,
                amount_cents,
                sender_ispb,
                receiver_ispb,
                sender_delta_cents,
                receiver_delta_cents,
                reason
            )
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
                reason
            FROM unnest(
                ?::text[],
                ?::payment_audit_event_type[],
                ?::payment_status[],
                ?::payment_status[],
                ?::bigint[],
                ?::text[],
                ?::text[],
                ?::bigint[],
                ?::bigint[],
                ?::payment_rejection_reason[]
            ) AS event(
                payment_id,
                event_type,
                previous_status,
                resulting_status,
                amount_cents,
                sender_ispb,
                receiver_ispb,
                sender_delta_cents,
                receiver_delta_cents,
                reason
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public PaymentAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(List<PaymentAuditEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            PaymentAuditArrays values = PaymentAuditArrays.from(events);
            Array paymentIds = null;
            Array eventTypes = null;
            Array previousStatuses = null;
            Array resultingStatuses = null;
            Array amounts = null;
            Array senderIspbs = null;
            Array receiverIspbs = null;
            Array senderDeltas = null;
            Array receiverDeltas = null;
            Array reasons = null;
            try {
                paymentIds = connection.createArrayOf("text", values.paymentIds());
                eventTypes = connection.createArrayOf("text", values.eventTypes());
                previousStatuses = connection.createArrayOf("text", values.previousStatuses());
                resultingStatuses = connection.createArrayOf("text", values.resultingStatuses());
                amounts = connection.createArrayOf("int8", values.amounts());
                senderIspbs = connection.createArrayOf("text", values.senderIspbs());
                receiverIspbs = connection.createArrayOf("text", values.receiverIspbs());
                senderDeltas = connection.createArrayOf("int8", values.senderDeltas());
                receiverDeltas = connection.createArrayOf("int8", values.receiverDeltas());
                reasons = connection.createArrayOf("text", values.reasons());

                try (var statement = connection.prepareStatement(INSERT_SQL)) {
                    statement.setArray(1, paymentIds);
                    statement.setArray(2, eventTypes);
                    statement.setArray(3, previousStatuses);
                    statement.setArray(4, resultingStatuses);
                    statement.setArray(5, amounts);
                    statement.setArray(6, senderIspbs);
                    statement.setArray(7, receiverIspbs);
                    statement.setArray(8, senderDeltas);
                    statement.setArray(9, receiverDeltas);
                    statement.setArray(10, reasons);
                    return statement.executeUpdate();
                }
            } finally {
                free(
                        paymentIds,
                        eventTypes,
                        previousStatuses,
                        resultingStatuses,
                        amounts,
                        senderIspbs,
                        receiverIspbs,
                        senderDeltas,
                        receiverDeltas,
                        reasons
                );
            }
        });
    }

    private static void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }

    private record PaymentAuditArrays(
            String[] paymentIds,
            String[] eventTypes,
            String[] previousStatuses,
            String[] resultingStatuses,
            Long[] amounts,
            String[] senderIspbs,
            String[] receiverIspbs,
            Long[] senderDeltas,
            Long[] receiverDeltas,
            String[] reasons
    ) {
        private static PaymentAuditArrays from(List<PaymentAuditEvent> events) {
            int size = events.size();
            String[] paymentIds = new String[size];
            String[] eventTypes = new String[size];
            String[] previousStatuses = new String[size];
            String[] resultingStatuses = new String[size];
            Long[] amounts = new Long[size];
            String[] senderIspbs = new String[size];
            String[] receiverIspbs = new String[size];
            Long[] senderDeltas = new Long[size];
            Long[] receiverDeltas = new Long[size];
            String[] reasons = new String[size];

            for (int index = 0; index < size; index++) {
                PaymentAuditEvent event = events.get(index);
                paymentIds[index] = event.paymentId();
                eventTypes[index] = event.eventType() == null ? null : event.eventType().name();
                previousStatuses[index] = statusName(event.previousStatus());
                resultingStatuses[index] = statusName(event.resultingStatus());
                amounts[index] = event.amountCents();
                senderIspbs[index] = event.senderIspb();
                receiverIspbs[index] = event.receiverIspb();
                senderDeltas[index] = event.senderDeltaCents();
                receiverDeltas[index] = event.receiverDeltaCents();
                reasons[index] = event.reason() == null ? null : event.reason().name();
            }

            return new PaymentAuditArrays(
                    paymentIds,
                    eventTypes,
                    previousStatuses,
                    resultingStatuses,
                    amounts,
                    senderIspbs,
                    receiverIspbs,
                    senderDeltas,
                    receiverDeltas,
                    reasons
            );
        }

        private static String statusName(PaymentStatus status) {
            return status == null ? null : status.name();
        }
    }
}
