package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
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
import java.util.Objects;
import java.util.Set;

class IncomingPaymentRequestPersistence {

    private static final String ACCEPTANCE_REQUEST = "ACCEPTANCE_REQUEST";
    private static final String DIVERGENT_DUPLICATE = "DIVERGENT_DUPLICATE";

    private static final String PERSISTENCE_SQL = """
            WITH incoming AS (
                SELECT *
                FROM unnest(
                    ?::int[],
                    ?::text[],
                    ?::bigint[],
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::text[]
                ) AS i(
                    ordinal,
                    payment_id,
                    amount_cents,
                    status,
                    sender_bank_code,
                    receiver_bank_code,
                    request_fingerprint,
                    request_fingerprint_version
                )
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
        return jdbcTemplate.execute((ConnectionCallback<List<PersistenceActionRow>>) connection -> {
            IncomingPaymentArrays incoming = incomingPaymentArrays(incomingRows);
            Array ordinalArray = null;
            Array paymentIdArray = null;
            Array amountCentsArray = null;
            Array statusArray = null;
            Array senderBankCodeArray = null;
            Array receiverBankCodeArray = null;
            Array requestFingerprintArray = null;
            Array requestFingerprintVersionArray = null;
            try {
                ordinalArray = connection.createArrayOf("int4", incoming.ordinals());
                paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
                amountCentsArray = connection.createArrayOf("int8", incoming.amountCents());
                statusArray = connection.createArrayOf("text", incoming.statuses());
                senderBankCodeArray = connection.createArrayOf("text", incoming.senderBankCodes());
                receiverBankCodeArray = connection.createArrayOf("text", incoming.receiverBankCodes());
                requestFingerprintArray = connection.createArrayOf("text", incoming.requestFingerprints());
                requestFingerprintVersionArray = connection.createArrayOf("text", incoming.requestFingerprintVersions());

                try (var statement = connection.prepareStatement(PERSISTENCE_SQL)) {
                    statement.setArray(1, ordinalArray);
                    statement.setArray(2, paymentIdArray);
                    statement.setArray(3, amountCentsArray);
                    statement.setArray(4, statusArray);
                    statement.setArray(5, senderBankCodeArray);
                    statement.setArray(6, receiverBankCodeArray);
                    statement.setArray(7, requestFingerprintArray);
                    statement.setArray(8, requestFingerprintVersionArray);
                    statement.setString(9, PaymentStatus.WAITING_ACCEPTANCE.name());

                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<PersistenceActionRow> actionRows = new ArrayList<>(incomingRows.size());
                        while (resultSet.next()) {
                            actionRows.add(new PersistenceActionRow(
                                    resultSet.getInt(1),
                                    resultSet.getString(2)
                            ));
                        }
                        return actionRows;
                    }
                }
            } finally {
                free(
                        ordinalArray,
                        paymentIdArray,
                        amountCentsArray,
                        statusArray,
                        senderBankCodeArray,
                        receiverBankCodeArray,
                        requestFingerprintArray,
                        requestFingerprintVersionArray
                );
            }
        });
    }

    private BatchLocalPaymentClassification classifyPaymentRequestsWithinBatch(
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        Map<String, List<IncomingPaymentRow>> rowsByPaymentId =
                new LinkedHashMap<>(mapCapacity(paymentTransactions.size()));
        List<IncomingPaymentRow> allIncomingRows = incomingRows(paymentTransactions);
        for (IncomingPaymentRow incomingRow : allIncomingRows) {
            rowsByPaymentId.computeIfAbsent(
                    incomingRow.paymentTransaction().getPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(incomingRow);
        }

        List<IncomingPaymentRow> logicalRows = new ArrayList<>(rowsByPaymentId.size());
        List<Integer> divergentDuplicateOrdinals = new ArrayList<>();
        Map<String, List<Integer>> originalOrdinalsByPaymentId = new LinkedHashMap<>(mapCapacity(rowsByPaymentId.size()));

        for (var entry : rowsByPaymentId.entrySet()) {
            List<IncomingPaymentRow> paymentRows = entry.getValue();
            List<Integer> originalOrdinals = new ArrayList<>(paymentRows.size());
            IncomingPaymentRow firstPaymentRow = paymentRows.get(0);
            boolean divergent = false;
            for (IncomingPaymentRow paymentRow : paymentRows) {
                originalOrdinals.add(paymentRow.ordinal());
                if (!sameFingerprintIdentity(firstPaymentRow, paymentRow)) {
                    divergent = true;
                }
            }
            originalOrdinalsByPaymentId.put(entry.getKey(), originalOrdinals);

            if (divergent) {
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

    private boolean sameFingerprintIdentity(IncomingPaymentRow firstPaymentRow, IncomingPaymentRow paymentRow) {
        return Objects.equals(firstPaymentRow.requestFingerprintVersion(), paymentRow.requestFingerprintVersion())
                && Objects.equals(firstPaymentRow.requestFingerprint(), paymentRow.requestFingerprint());
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

    private IncomingPaymentArrays incomingPaymentArrays(List<IncomingPaymentRow> incomingRows) {
        int size = incomingRows.size();
        Integer[] ordinals = new Integer[size];
        String[] paymentIds = new String[size];
        Long[] amountCents = new Long[size];
        String[] statuses = new String[size];
        String[] senderBankCodes = new String[size];
        String[] receiverBankCodes = new String[size];
        String[] requestFingerprints = new String[size];
        String[] requestFingerprintVersions = new String[size];

        for (int index = 0; index < incomingRows.size(); index++) {
            IncomingPaymentRow incomingRow = incomingRows.get(index);
            PaymentTransactionCommand paymentTransaction = incomingRow.paymentTransaction();
            ordinals[index] = incomingRow.ordinal();
            paymentIds[index] = paymentTransaction.getPaymentId();
            amountCents[index] = paymentTransaction.getAmountCents();
            statuses[index] = PaymentStatus.WAITING_ACCEPTANCE.name();
            senderBankCodes[index] = Utils.getBankCode(paymentTransaction.getSender());
            receiverBankCodes[index] = Utils.getBankCode(paymentTransaction.getReceiver());
            requestFingerprints[index] = incomingRow.requestFingerprint();
            requestFingerprintVersions[index] = incomingRow.requestFingerprintVersion();
        }

        return new IncomingPaymentArrays(
                ordinals,
                paymentIds,
                amountCents,
                statuses,
                senderBankCodes,
                receiverBankCodes,
                requestFingerprints,
                requestFingerprintVersions
        );
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

    private record IncomingPaymentRow(
            int ordinal,
            PaymentTransactionCommand paymentTransaction,
            String requestFingerprint,
            String requestFingerprintVersion
    ) {
    }

    private record PersistenceActionRow(int ordinal, String action) {
    }

    private record BatchLocalPaymentClassification(
            List<IncomingPaymentRow> incomingRows,
            List<Integer> sameBatchDivergentOrdinals,
            Map<String, List<Integer>> originalOrdinalsByPaymentId
    ) {
    }

    private record IncomingPaymentArrays(
            Integer[] ordinals,
            String[] paymentIds,
            Long[] amountCents,
            String[] statuses,
            String[] senderBankCodes,
            String[] receiverBankCodes,
            String[] requestFingerprints,
            String[] requestFingerprintVersions
    ) {
        private IncomingPaymentArrays {
            int size = ordinals.length;
            if (paymentIds.length != size
                    || amountCents.length != size
                    || statuses.length != size
                    || senderBankCodes.length != size
                    || receiverBankCodes.length != size
                    || requestFingerprints.length != size
                    || requestFingerprintVersions.length != size) {
                throw new IllegalStateException("Incoming payment arrays must have the same size");
            }
        }
    }
}
