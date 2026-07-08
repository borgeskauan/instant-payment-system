package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class IncomingStatusReportPersistence {

    private static final String ACCEPTED_FOR_SETTLEMENT = "ACCEPTED_FOR_SETTLEMENT";
    private static final String REJECTED_NOTIFICATION = "REJECTED_NOTIFICATION";
    private static final String DIVERGENT_STATUS_REPORT = "DIVERGENT_STATUS_REPORT";

    private static final String STATUS_REPORT_SQL_TEMPLATE = """
            WITH incoming (
                ordinal,
                payment_id,
                requested_status
            ) AS (
                VALUES
                %s
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
                    p.receiver_bank_code
                FROM incoming i
                JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
                ORDER BY i.ordinal
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
            accepted_for_settlement_actions AS (
                SELECT
                    le.ordinal,
                    'ACCEPTED_FOR_SETTLEMENT'::text AS action,
                    le.payment_id,
                    NULL::bigint AS amount_cents,
                    NULL::text AS sender_bank_code,
                    NULL::text AS receiver_bank_code
                FROM locked_existing le
                WHERE le.requested_status = ?
                  AND le.existing_status = ?
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
            FROM accepted_for_settlement_actions
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
        List<String> acceptedPaymentIds = new ArrayList<>();
        List<PaymentTransactionCommand> rejectedPayments = new ArrayList<>();
        Set<Integer> divergentStatusReportOrdinals =
                new LinkedHashSet<>(batchLocalClassification.sameBatchDivergentOrdinals());

        if (batchLocalClassification.statusReportsToClassify().isEmpty()) {
            return new StatusReportPersistenceResult(
                    acceptedPaymentIds,
                    rejectedPayments,
                    divergentStatusReports(statusReports, divergentStatusReportOrdinals)
            );
        }

        for (StatusReportActionRow actionRow : classifyAndApplyStatusReports(batchLocalClassification.statusReportsToClassify())) {
            StatusReportCommand statusReport = statusReports.get(actionRow.ordinal());
            switch (actionRow.action()) {
                case ACCEPTED_FOR_SETTLEMENT -> acceptedPaymentIds.add(statusReport.getOriginalPaymentId());
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
                acceptedPaymentIds,
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
        Map<String, List<StatusReportRow>> rowsByPaymentId = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < statusReports.size(); ordinal++) {
            StatusReportCommand statusReport = statusReports.get(ordinal);
            rowsByPaymentId.computeIfAbsent(
                    statusReport.getOriginalPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(new StatusReportRow(ordinal, statusReport));
        }

        List<StatusReportRow> statusReportsToClassify = new ArrayList<>(rowsByPaymentId.size());
        List<Integer> divergentStatusReportOrdinals = new ArrayList<>();
        Map<String, List<Integer>> originalOrdinalsByPaymentId = new LinkedHashMap<>();

        for (var entry : rowsByPaymentId.entrySet()) {
            List<StatusReportRow> statusReportRows = entry.getValue();
            originalOrdinalsByPaymentId.put(
                    entry.getKey(),
                    statusReportRows.stream().map(StatusReportRow::ordinal).toList()
            );

            Set<PaymentStatus> statuses = new LinkedHashSet<>();
            for (StatusReportRow statusReportRow : statusReportRows) {
                statuses.add(statusReportRow.statusReport().getStatus());
            }

            if (statuses.size() > 1) {
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
        return jdbcTemplate.query(
                statusReportSql(statusReports.size()),
                statement -> {
                    int parameterIndex = 1;
                    for (StatusReportRow statusReportRow : statusReports) {
                        StatusReportCommand statusReport = statusReportRow.statusReport();
                        statement.setInt(parameterIndex++, statusReportRow.ordinal());
                        statement.setString(parameterIndex++, statusReport.getOriginalPaymentId());
                        statement.setString(parameterIndex++, statusReport.getStatus().name());
                    }
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
                    statement.setString(parameterIndex, PaymentStatus.WAITING_ACCEPTANCE.name());
                },
                (resultSet, rowNumber) -> {
                    Long amountCents = resultSet.getObject("amount_cents", Long.class);
                    return new StatusReportActionRow(
                            resultSet.getInt("ordinal"),
                            resultSet.getString("action"),
                            resultSet.getString("payment_id"),
                            amountCents,
                            resultSet.getString("sender_bank_code"),
                            resultSet.getString("receiver_bank_code")
                    );
                }
        );
    }

    private String statusReportSql(int rowCount) {
        String values = java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(ignored -> "(?, ?, ?)")
                .collect(Collectors.joining(",\n"));

        return STATUS_REPORT_SQL_TEMPLATE.formatted(values);
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
}
