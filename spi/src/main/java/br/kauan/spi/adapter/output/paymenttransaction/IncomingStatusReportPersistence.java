package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class IncomingStatusReportPersistence {

    private static final int BUCKET_COUNT = 16;
    private static final String SETTLED_PAYMENT = "SETTLED_PAYMENT";
    private static final String REJECTED_NOTIFICATION = "REJECTED_NOTIFICATION";
    private static final String DIVERGENT_STATUS_REPORT = "DIVERGENT_STATUS_REPORT";

    private static final String STATUS_REPORT_SQL = """
            WITH incoming AS (
                SELECT *
                FROM unnest(
                    ?::int[],
                    ?::text[],
                    ?::text[]
                ) AS i(
                    ordinal,
                    payment_id,
                    requested_status
                )
            ),
            unknown_actions AS (
                SELECT
                    i.ordinal,
                    'DIVERGENT_STATUS_REPORT'::text AS action,
                    i.payment_id,
                    NULL::bigint AS amount_cents,
                    NULL::text AS sender_bank_code,
                    NULL::text AS receiver_bank_code
                FROM incoming i
                LEFT JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
                WHERE p.payment_id IS NULL
            ),
            locked_existing AS MATERIALIZED (
                SELECT
                    i.ordinal,
                    i.payment_id,
                    i.requested_status,
                    p.status AS existing_status,
                    p.amount_cents,
                    p.sender_bank_code,
                    p.receiver_bank_code,
                    (ABS(hashtext(p.payment_id)) % ?) AS bucket_id
                FROM incoming i
                JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
                ORDER BY p.payment_id
                FOR UPDATE OF p
            ),
            rejected_updates AS (
                UPDATE payment_transaction_entity p
                SET status = ?
                FROM locked_existing le
                WHERE p.payment_id = le.payment_id
                  AND le.requested_status = ?
                  AND le.existing_status = ?
                  AND p.status = ?
                RETURNING
                    le.ordinal,
                    p.payment_id,
                    p.amount_cents,
                    p.sender_bank_code,
                    p.receiver_bank_code
            ),
            divergent_existing_actions AS (
                SELECT
                    le.ordinal,
                    'DIVERGENT_STATUS_REPORT'::text AS action,
                    le.payment_id,
                    NULL::bigint AS amount_cents,
                    NULL::text AS sender_bank_code,
                    NULL::text AS receiver_bank_code
                FROM locked_existing le
                WHERE le.requested_status NOT IN (?, ?)
                   OR (
                       le.requested_status = ?
                       AND le.existing_status NOT IN (?, ?, ?)
                   )
                   OR (
                       le.requested_status = ?
                       AND le.existing_status NOT IN (?, ?)
                   )
            ),
            accepted_waiting AS (
                SELECT *
                FROM locked_existing le
                WHERE le.requested_status = ?
                  AND le.existing_status = ?
            ),
            required_buckets AS (
                SELECT sender_bank_code AS bank_code, bucket_id
                FROM accepted_waiting
                UNION
                SELECT receiver_bank_code AS bank_code, bucket_id
                FROM accepted_waiting
            ),
            locked_buckets AS MATERIALIZED (
                SELECT f.bank_code, f.bucket_id, f.balance_cents
                FROM funds_bucket_entity f
                JOIN required_buckets b
                  ON b.bank_code = f.bank_code
                 AND b.bucket_id = f.bucket_id
                ORDER BY f.bank_code, f.bucket_id
                FOR UPDATE OF f
            ),
            ranked AS (
                SELECT aw.*,
                       SUM(aw.amount_cents) OVER (
                           PARTITION BY aw.sender_bank_code, aw.bucket_id
                           ORDER BY aw.ordinal
                       ) AS cumulative_debit_cents
                FROM accepted_waiting aw
            ),
            settleable AS (
                SELECT r.*
                FROM ranked r
                JOIN locked_buckets sender_bucket
                  ON sender_bucket.bank_code = r.sender_bank_code
                 AND sender_bucket.bucket_id = r.bucket_id
                WHERE sender_bucket.balance_cents >= r.cumulative_debit_cents
                  AND EXISTS (
                      SELECT 1
                      FROM locked_buckets receiver_bucket
                      WHERE receiver_bucket.bank_code = r.receiver_bank_code
                        AND receiver_bucket.bucket_id = r.bucket_id
                  )
            ),
            deltas AS (
                SELECT sender_bank_code AS bank_code,
                       bucket_id,
                       -SUM(amount_cents) AS delta_cents
                FROM settleable
                GROUP BY sender_bank_code, bucket_id
                UNION ALL
                SELECT receiver_bank_code AS bank_code,
                       bucket_id,
                       SUM(amount_cents) AS delta_cents
                FROM settleable
                GROUP BY receiver_bank_code, bucket_id
            ),
            net_deltas AS (
                SELECT bank_code, bucket_id, SUM(delta_cents) AS delta_cents
                FROM deltas
                GROUP BY bank_code, bucket_id
            ),
            updated_funds AS (
                UPDATE funds_bucket_entity f
                SET balance_cents = f.balance_cents + d.delta_cents
                FROM net_deltas d
                WHERE f.bank_code = d.bank_code
                  AND f.bucket_id = d.bucket_id
                RETURNING f.bank_code
            ),
            funds_applied AS (
                SELECT COUNT(*) AS applied_count
                FROM updated_funds
            ),
            settled_updates AS (
                UPDATE payment_transaction_entity p
                SET status = ?
                FROM settleable s, funds_applied fa
                WHERE p.payment_id = s.payment_id
                  AND p.status = ?
                RETURNING
                    s.ordinal,
                    p.payment_id,
                    p.amount_cents,
                    p.sender_bank_code,
                    p.receiver_bank_code
            ),
            accepted_in_process_updates AS (
                UPDATE payment_transaction_entity p
                SET status = ?
                FROM accepted_waiting aw
                LEFT JOIN settleable s ON s.payment_id = aw.payment_id
                WHERE p.payment_id = aw.payment_id
                  AND s.payment_id IS NULL
                  AND p.status = ?
                RETURNING p.payment_id
            ),
            settled_payment_actions AS (
                SELECT
                    ordinal,
                    'SETTLED_PAYMENT'::text AS action,
                    payment_id,
                    amount_cents,
                    sender_bank_code,
                    receiver_bank_code
                FROM settled_updates
            ),
            rejected_notification_actions AS (
                SELECT
                    ordinal,
                    'REJECTED_NOTIFICATION'::text AS action,
                    payment_id,
                    amount_cents,
                    sender_bank_code,
                    receiver_bank_code
                FROM rejected_updates
            )
            SELECT ordinal, action, payment_id, amount_cents, sender_bank_code, receiver_bank_code
            FROM unknown_actions
            UNION ALL
            SELECT ordinal, action, payment_id, amount_cents, sender_bank_code, receiver_bank_code
            FROM divergent_existing_actions
            UNION ALL
            SELECT ordinal, action, payment_id, amount_cents, sender_bank_code, receiver_bank_code
            FROM settled_payment_actions
            UNION ALL
            SELECT ordinal, action, payment_id, amount_cents, sender_bank_code, receiver_bank_code
            FROM rejected_notification_actions
            ORDER BY ordinal
            """;

    private final Mapper repositoryMapper;
    private final JdbcTemplate jdbcTemplate;

    IncomingStatusReportPersistence(
            Mapper repositoryMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.repositoryMapper = repositoryMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    StatusReportPersistenceResult classifyAndApply(List<StatusReportCommand> statusReports) {
        if (statusReports.isEmpty()) {
            return new StatusReportPersistenceResult(List.of(), List.of(), List.of());
        }

        BatchLocalStatusReportClassification batchLocalClassification =
                classifyStatusReportsWithinBatch(statusReports);
        List<PaymentTransactionCommand> settledPayments = new ArrayList<>();
        List<PaymentTransactionCommand> rejectedPayments = new ArrayList<>();
        Set<Integer> divergentStatusReportOrdinals =
                new LinkedHashSet<>(batchLocalClassification.sameBatchDivergentOrdinals());

        if (batchLocalClassification.statusReportsToClassify().isEmpty()) {
            return new StatusReportPersistenceResult(
                    settledPayments,
                    rejectedPayments,
                    divergentStatusReports(statusReports, divergentStatusReportOrdinals)
            );
        }

        for (StatusReportActionRow actionRow : classifyAndApplyStatusReports(batchLocalClassification.statusReportsToClassify())) {
            StatusReportCommand statusReport = statusReports.get(actionRow.ordinal());
            switch (actionRow.action()) {
                case SETTLED_PAYMENT -> settledPayments.add(toPaymentTransaction(actionRow));
                case REJECTED_NOTIFICATION -> rejectedPayments.add(toPaymentTransaction(actionRow));
                case DIVERGENT_STATUS_REPORT -> addOriginalBatchRecordOrdinals(
                        divergentStatusReportOrdinals,
                        batchLocalClassification.originalOrdinalsByPaymentId(),
                        statusReport.getOriginalPaymentId()
                );
                default -> throw new IllegalStateException("Unknown status report action: " + actionRow.action());
            }
        }

        return new StatusReportPersistenceResult(
                settledPayments,
                rejectedPayments,
                divergentStatusReports(statusReports, divergentStatusReportOrdinals)
        );
    }

    private void addOriginalBatchRecordOrdinals(
            Set<Integer> divergentDuplicateOrdinals,
            Map<String, List<Integer>> originalOrdinalsByPaymentId,
            String paymentId
    ) {
        divergentDuplicateOrdinals.addAll(originalOrdinalsByPaymentId.get(paymentId));
    }

    private BatchLocalStatusReportClassification classifyStatusReportsWithinBatch(
            List<StatusReportCommand> statusReports
    ) {
        Map<String, List<StatusReportRow>> rowsByPaymentId =
                new LinkedHashMap<>(mapCapacity(statusReports.size()));
        for (int ordinal = 0; ordinal < statusReports.size(); ordinal++) {
            StatusReportCommand statusReport = statusReports.get(ordinal);
            rowsByPaymentId.computeIfAbsent(
                    statusReport.getOriginalPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(new StatusReportRow(ordinal, statusReport));
        }

        List<StatusReportRow> statusReportsToClassify = new ArrayList<>(rowsByPaymentId.size());
        List<Integer> divergentStatusReportOrdinals = new ArrayList<>();
        Map<String, List<Integer>> originalOrdinalsByPaymentId = new LinkedHashMap<>(mapCapacity(rowsByPaymentId.size()));

        for (var entry : rowsByPaymentId.entrySet()) {
            List<StatusReportRow> statusReportRows = entry.getValue();
            List<Integer> originalOrdinals = new ArrayList<>(statusReportRows.size());
            PaymentStatus firstStatus = statusReportRows.get(0).statusReport().getStatus();
            boolean divergent = false;
            for (StatusReportRow statusReportRow : statusReportRows) {
                originalOrdinals.add(statusReportRow.ordinal());
                if (statusReportRow.statusReport().getStatus() != firstStatus) {
                    divergent = true;
                }
            }
            originalOrdinalsByPaymentId.put(entry.getKey(), originalOrdinals);

            if (divergent) {
                for (StatusReportRow statusReportRow : statusReportRows) {
                    divergentStatusReportOrdinals.add(statusReportRow.ordinal());
                }
            } else {
                statusReportsToClassify.add(statusReportRows.get(0));
            }
        }

        return new BatchLocalStatusReportClassification(
                statusReportsToClassify,
                divergentStatusReportOrdinals,
                originalOrdinalsByPaymentId
        );
    }

    private List<StatusReportCommand> divergentStatusReports(
            List<StatusReportCommand> statusReports,
            Set<Integer> divergentStatusReportOrdinals
    ) {
        List<StatusReportCommand> divergentStatusReports = new ArrayList<>(divergentStatusReportOrdinals.size());
        for (int ordinal = 0; ordinal < statusReports.size(); ordinal++) {
            if (divergentStatusReportOrdinals.contains(ordinal)) {
                divergentStatusReports.add(statusReports.get(ordinal));
            }
        }
        return divergentStatusReports;
    }

    private List<StatusReportActionRow> classifyAndApplyStatusReports(List<StatusReportRow> statusReports) {
        return jdbcTemplate.execute((ConnectionCallback<List<StatusReportActionRow>>) connection -> {
            IncomingStatusReportArrays incoming = incomingStatusReportArrays(statusReports);
            Array ordinalArray = null;
            Array paymentIdArray = null;
            Array requestedStatusArray = null;
            try {
                ordinalArray = connection.createArrayOf("int4", incoming.ordinals());
                paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
                requestedStatusArray = connection.createArrayOf("text", incoming.requestedStatuses());

                try (var statement = connection.prepareStatement(STATUS_REPORT_SQL)) {
                    int parameterIndex = 1;
                    statement.setArray(parameterIndex++, ordinalArray);
                    statement.setArray(parameterIndex++, paymentIdArray);
                    statement.setArray(parameterIndex++, requestedStatusArray);
                    statement.setInt(parameterIndex++, BUCKET_COUNT);

                    statement.setString(parameterIndex++, PaymentStatus.REJECTED.name());
                    statement.setString(parameterIndex++, PaymentStatus.REJECTED.name());
                    statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());
                    statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());

                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_IN_PROCESS.name());
                    statement.setString(parameterIndex++, PaymentStatus.REJECTED.name());
                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_IN_PROCESS.name());
                    statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());
                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_IN_PROCESS.name());
                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_AND_SETTLED.name());
                    statement.setString(parameterIndex++, PaymentStatus.REJECTED.name());
                    statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());
                    statement.setString(parameterIndex++, PaymentStatus.REJECTED.name());

                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_IN_PROCESS.name());
                    statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());

                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_AND_SETTLED.name());
                    statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());
                    statement.setString(parameterIndex++, PaymentStatus.ACCEPTED_IN_PROCESS.name());
                    statement.setString(parameterIndex, PaymentStatus.WAITING_ACCEPTANCE.name());

                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<StatusReportActionRow> actionRows = new ArrayList<>(statusReports.size());
                        while (resultSet.next()) {
                            Long amountCents = resultSet.getObject(4, Long.class);
                            actionRows.add(new StatusReportActionRow(
                                    resultSet.getInt(1),
                                    resultSet.getString(2),
                                    resultSet.getString(3),
                                    amountCents,
                                    resultSet.getString(5),
                                    resultSet.getString(6)
                            ));
                        }
                        return actionRows;
                    }
                }
            } finally {
                free(ordinalArray, paymentIdArray, requestedStatusArray);
            }
        });
    }

    private IncomingStatusReportArrays incomingStatusReportArrays(List<StatusReportRow> statusReports) {
        int size = statusReports.size();
        Integer[] ordinals = new Integer[size];
        String[] paymentIds = new String[size];
        String[] requestedStatuses = new String[size];

        for (int index = 0; index < statusReports.size(); index++) {
            StatusReportRow statusReportRow = statusReports.get(index);
            StatusReportCommand statusReport = statusReportRow.statusReport();
            ordinals[index] = statusReportRow.ordinal();
            paymentIds[index] = statusReport.getOriginalPaymentId();
            requestedStatuses[index] = statusReport.getStatus().name();
        }

        return new IncomingStatusReportArrays(ordinals, paymentIds, requestedStatuses);
    }

    private void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }

    private int mapCapacity(int expectedSize) {
        return Math.max(16, expectedSize * 4 / 3 + 1);
    }

    private PaymentTransactionCommand toPaymentTransaction(StatusReportActionRow actionRow) {
        Entity entity = new Entity();
        entity.setPaymentId(actionRow.paymentId());
        entity.setAmountCents(actionRow.amountCents());
        entity.setSenderBankCode(actionRow.senderBankCode());
        entity.setReceiverBankCode(actionRow.receiverBankCode());
        return repositoryMapper.toDomain(entity);
    }

    private record StatusReportRow(
            int ordinal,
            StatusReportCommand statusReport
    ) {
    }

    private record BatchLocalStatusReportClassification(
            List<StatusReportRow> statusReportsToClassify,
            List<Integer> sameBatchDivergentOrdinals,
            Map<String, List<Integer>> originalOrdinalsByPaymentId
    ) {
    }

    private record StatusReportActionRow(
            int ordinal,
            String action,
            String paymentId,
            Long amountCents,
            String senderBankCode,
            String receiverBankCode
    ) {
    }

    private record IncomingStatusReportArrays(
            Integer[] ordinals,
            String[] paymentIds,
            String[] requestedStatuses
    ) {
        private IncomingStatusReportArrays {
            int size = ordinals.length;
            if (paymentIds.length != size || requestedStatuses.length != size) {
                throw new IllegalStateException("Incoming status report arrays must have the same size");
            }
        }
    }
}
