package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class IncomingPaymentRequestPersistence {

    private static final String ACCEPTANCE_REQUEST = "ACCEPTANCE_REQUEST";
    private static final String DIVERGENT_DUPLICATE = "DIVERGENT_DUPLICATE";

    private static final String PERSISTENCE_SQL_TEMPLATE = """
            WITH incoming (
                ordinal,
                payment_id,
                amount_cents,
                status,
                sender_bank_code,
                receiver_bank_code,
                request_fingerprint,
                request_fingerprint_version
            ) AS (
                VALUES
                %s
            ),
            inserted AS (
                INSERT INTO payment_transaction_entity (
                    payment_id,
                    amount_cents,
                    status,
                    sender_bank_code,
                    receiver_bank_code,
                    request_fingerprint,
                    request_fingerprint_version
                )
                SELECT
                    payment_id,
                    amount_cents,
                    status,
                    sender_bank_code,
                    receiver_bank_code,
                    request_fingerprint,
                    request_fingerprint_version
                FROM incoming
                ON CONFLICT (payment_id) DO NOTHING
                RETURNING payment_id
            ),
            inserted_actions AS (
                SELECT i.ordinal, 'ACCEPTANCE_REQUEST'::text AS action
                FROM incoming i
                JOIN inserted ins ON ins.payment_id = i.payment_id
            ),
            existing_rows AS (
                SELECT
                    i.payment_id,
                    i.ordinal,
                    i.request_fingerprint,
                    i.request_fingerprint_version,
                    p.status AS existing_status,
                    p.request_fingerprint AS existing_fingerprint,
                    p.request_fingerprint_version AS existing_fingerprint_version
                FROM incoming i
                JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
                LEFT JOIN inserted ins ON ins.payment_id = i.payment_id
                WHERE ins.payment_id IS NULL
            ),
            existing_waiting_acceptance_actions AS (
                SELECT er.ordinal, 'ACCEPTANCE_REQUEST'::text AS action
                FROM existing_rows er
                WHERE er.existing_fingerprint_version = er.request_fingerprint_version
                  AND er.existing_fingerprint = er.request_fingerprint
                  AND er.existing_status = ?
            ),
            existing_divergent_payment_ids AS (
                SELECT er.payment_id
                FROM existing_rows er
                WHERE er.existing_fingerprint_version IS DISTINCT FROM er.request_fingerprint_version
                   OR er.existing_fingerprint IS DISTINCT FROM er.request_fingerprint
            ),
            existing_divergent_actions AS (
                SELECT er.ordinal, 'DIVERGENT_DUPLICATE'::text AS action
                FROM existing_rows er
                JOIN existing_divergent_payment_ids d ON d.payment_id = er.payment_id
            )
            SELECT ordinal, action FROM inserted_actions
            UNION ALL
            SELECT ordinal, action FROM existing_waiting_acceptance_actions
            UNION ALL
            SELECT ordinal, action FROM existing_divergent_actions
            ORDER BY ordinal
            """;

    private final JdbcTemplate jdbcTemplate;

    IncomingPaymentRequestPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    PaymentTransactionPersistenceResult storeAndClassify(List<PaymentTransactionCommand> paymentTransactions) {
        if (paymentTransactions.isEmpty()) {
            return new PaymentTransactionPersistenceResult(List.of(), List.of());
        }

        List<PaymentTransactionCommand> acceptanceRequests = new ArrayList<>();
        BatchLocalPaymentClassification batchLocalClassification =
                classifyPaymentRequestsWithinBatch(paymentTransactions);
        Set<Integer> divergentDuplicateOrdinals =
                new LinkedHashSet<>(batchLocalClassification.sameBatchDivergentOrdinals());

        if (batchLocalClassification.incomingRows().isEmpty()) {
            return new PaymentTransactionPersistenceResult(
                    acceptanceRequests,
                    divergentDuplicates(paymentTransactions, divergentDuplicateOrdinals)
            );
        }

        for (PersistenceActionRow actionRow : persistAndClassify(batchLocalClassification.incomingRows())) {
            PaymentTransactionCommand paymentTransaction = paymentTransactions.get(actionRow.ordinal());
            switch (actionRow.action()) {
                case ACCEPTANCE_REQUEST -> acceptanceRequests.add(paymentTransaction);
                case DIVERGENT_DUPLICATE -> addOriginalBatchRecordOrdinals(
                        divergentDuplicateOrdinals,
                        batchLocalClassification.originalOrdinalsByPaymentId(),
                        paymentTransaction.getPaymentId()
                );
                default -> throw new IllegalStateException("Unknown payment persistence action: " + actionRow.action());
            }
        }

        return new PaymentTransactionPersistenceResult(
                acceptanceRequests,
                divergentDuplicates(paymentTransactions, divergentDuplicateOrdinals)
        );
    }

    private void addOriginalBatchRecordOrdinals(
            Set<Integer> divergentDuplicateOrdinals,
            Map<String, List<Integer>> originalOrdinalsByPaymentId,
            String paymentId
    ) {
        divergentDuplicateOrdinals.addAll(originalOrdinalsByPaymentId.get(paymentId));
    }

    private List<PaymentTransactionCommand> divergentDuplicates(
            List<PaymentTransactionCommand> paymentTransactions,
            Set<Integer> divergentDuplicateOrdinals
    ) {
        List<PaymentTransactionCommand> divergentDuplicates = new ArrayList<>(divergentDuplicateOrdinals.size());
        for (int ordinal = 0; ordinal < paymentTransactions.size(); ordinal++) {
            if (divergentDuplicateOrdinals.contains(ordinal)) {
                divergentDuplicates.add(paymentTransactions.get(ordinal));
            }
        }
        return divergentDuplicates;
    }

    private List<PersistenceActionRow> persistAndClassify(
            List<IncomingPaymentRow> incomingRows
    ) {
        return jdbcTemplate.query(
                persistenceSql(incomingRows.size()),
                statement -> {
                    int parameterIndex = 1;
                    for (IncomingPaymentRow incomingRow : incomingRows) {
                        statement.setInt(parameterIndex++, incomingRow.ordinal());
                        statement.setString(parameterIndex++, incomingRow.paymentTransaction().getPaymentId());
                        statement.setLong(parameterIndex++, incomingRow.paymentTransaction().getAmountCents());
                        statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());
                        statement.setString(parameterIndex++, Utils.getBankCode(incomingRow.paymentTransaction().getSender()));
                        statement.setString(parameterIndex++, Utils.getBankCode(incomingRow.paymentTransaction().getReceiver()));
                        statement.setString(parameterIndex++, incomingRow.requestFingerprint());
                        statement.setString(parameterIndex++, incomingRow.requestFingerprintVersion());
                    }
                    statement.setString(parameterIndex, PaymentStatus.WAITING_ACCEPTANCE.name());
                },
                (resultSet, rowNumber) -> new PersistenceActionRow(
                        resultSet.getInt("ordinal"),
                        resultSet.getString("action")
                )
        );
    }

    private BatchLocalPaymentClassification classifyPaymentRequestsWithinBatch(
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        Map<String, List<IncomingPaymentRow>> rowsByPaymentId = new LinkedHashMap<>();
        List<IncomingPaymentRow> allIncomingRows = incomingRows(paymentTransactions);
        for (IncomingPaymentRow incomingRow : allIncomingRows) {
            rowsByPaymentId.computeIfAbsent(
                    incomingRow.paymentTransaction().getPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(incomingRow);
        }

        List<IncomingPaymentRow> logicalRows = new ArrayList<>(rowsByPaymentId.size());
        List<Integer> divergentDuplicateOrdinals = new ArrayList<>();
        Map<String, List<Integer>> originalOrdinalsByPaymentId = new LinkedHashMap<>();

        for (var entry : rowsByPaymentId.entrySet()) {
            List<IncomingPaymentRow> paymentRows = entry.getValue();
            originalOrdinalsByPaymentId.put(
                    entry.getKey(),
                    paymentRows.stream().map(IncomingPaymentRow::ordinal).toList()
            );

            Set<FingerprintIdentity> fingerprintIdentities = new LinkedHashSet<>();
            for (IncomingPaymentRow paymentRow : paymentRows) {
                fingerprintIdentities.add(new FingerprintIdentity(
                        paymentRow.requestFingerprintVersion(),
                        paymentRow.requestFingerprint()
                ));
            }

            if (fingerprintIdentities.size() > 1) {
                for (IncomingPaymentRow paymentRow : paymentRows) {
                    divergentDuplicateOrdinals.add(paymentRow.ordinal());
                }
            } else {
                logicalRows.add(paymentRows.get(0));
            }
        }

        return new BatchLocalPaymentClassification(
                logicalRows,
                divergentDuplicateOrdinals,
                originalOrdinalsByPaymentId
        );
    }

    private List<IncomingPaymentRow> incomingRows(List<PaymentTransactionCommand> paymentTransactions) {
        List<IncomingPaymentRow> incomingRows = new ArrayList<>(paymentTransactions.size());
        for (int ordinal = 0; ordinal < paymentTransactions.size(); ordinal++) {
            PaymentTransactionCommand paymentTransaction = paymentTransactions.get(ordinal);
            incomingRows.add(new IncomingPaymentRow(
                    ordinal,
                    paymentTransaction,
                    RequestFingerprint.from(paymentTransaction),
                    RequestFingerprint.VERSION
            ));
        }
        return incomingRows;
    }

    private String persistenceSql(int rowCount) {
        String values = java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(ignored -> "(?, ?, ?, ?, ?, ?, ?, ?)")
                .collect(Collectors.joining(",\n"));

        return PERSISTENCE_SQL_TEMPLATE.formatted(values);
    }

    private record IncomingPaymentRow(
            int ordinal,
            PaymentTransactionCommand paymentTransaction,
            String requestFingerprint,
            String requestFingerprintVersion
    ) {
    }

    private record PersistenceActionRow(int ordinal, String action) {
    }

    private record FingerprintIdentity(
            String requestFingerprintVersion,
            String requestFingerprint
    ) {
    }

    private record BatchLocalPaymentClassification(
            List<IncomingPaymentRow> incomingRows,
            List<Integer> sameBatchDivergentOrdinals,
            Map<String, List<Integer>> originalOrdinalsByPaymentId
    ) {
    }
}
