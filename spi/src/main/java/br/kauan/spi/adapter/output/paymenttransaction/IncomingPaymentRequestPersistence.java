package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
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
    private static final String UNAUTHORIZED_PSP = "UNAUTHORIZED_PSP";

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
                    request_fingerprint_version,
                    authenticated_ispb
                )
            ),
            payload_unauthorized_actions AS (
                SELECT ordinal, 'UNAUTHORIZED_PSP'::text AS action
                FROM incoming
                WHERE authenticated_ispb IS DISTINCT FROM sender_bank_code
            ),
            payload_authorized AS (
                SELECT *
                FROM incoming
                WHERE authenticated_ispb IS NOT DISTINCT FROM sender_bank_code
            ),
            existing_lookup AS MATERIALIZED (
                SELECT
                    i.*,
                    p.payment_id AS existing_payment_id,
                    p.status AS existing_status,
                    p.sender_bank_code AS existing_sender_bank_code,
                    p.request_fingerprint AS existing_fingerprint,
                    p.request_fingerprint_version AS existing_fingerprint_version
                FROM payload_authorized i
                LEFT JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
            ),
            existing_unauthorized_actions AS (
                SELECT ordinal, 'UNAUTHORIZED_PSP'::text AS action
                FROM existing_lookup
                WHERE existing_payment_id IS NOT NULL
                  AND authenticated_ispb IS DISTINCT FROM existing_sender_bank_code
            ),
            authorized_incoming AS (
                SELECT *
                FROM existing_lookup
                WHERE existing_payment_id IS NULL
                   OR authenticated_ispb IS NOT DISTINCT FROM existing_sender_bank_code
            ),
            authorized_group_stats AS (
                SELECT
                    payment_id,
                    COUNT(DISTINCT (request_fingerprint_version, request_fingerprint)) > 1
                        AS divergent
                FROM authorized_incoming
                GROUP BY payment_id
            ),
            same_batch_divergent_actions AS (
                SELECT ai.ordinal, 'DIVERGENT_DUPLICATE'::text AS action
                FROM authorized_incoming ai
                JOIN authorized_group_stats stats USING (payment_id)
                WHERE stats.divergent
            ),
            logical_incoming AS (
                SELECT DISTINCT ON (ai.payment_id) ai.*
                FROM authorized_incoming ai
                JOIN authorized_group_stats stats USING (payment_id)
                WHERE NOT stats.divergent
                ORDER BY ai.payment_id, ai.ordinal
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
                FROM logical_incoming
                WHERE existing_payment_id IS NULL
                ON CONFLICT (payment_id) DO NOTHING
                RETURNING payment_id
            ),
            inserted_actions AS (
                SELECT i.ordinal, 'ACCEPTANCE_REQUEST'::text AS action
                FROM logical_incoming i
                JOIN inserted ins ON ins.payment_id = i.payment_id
            ),
            existing_waiting_acceptance_actions AS (
                SELECT li.ordinal, 'ACCEPTANCE_REQUEST'::text AS action
                FROM logical_incoming li
                WHERE li.existing_payment_id IS NOT NULL
                  AND li.existing_fingerprint_version = li.request_fingerprint_version
                  AND li.existing_fingerprint = li.request_fingerprint
                  AND li.existing_status = ?
            ),
            existing_divergent_payment_ids AS (
                SELECT li.payment_id
                FROM logical_incoming li
                WHERE li.existing_payment_id IS NOT NULL
                  AND (
                      li.existing_fingerprint_version IS DISTINCT FROM li.request_fingerprint_version
                      OR li.existing_fingerprint IS DISTINCT FROM li.request_fingerprint
                  )
            ),
            existing_divergent_actions AS (
                SELECT ai.ordinal, 'DIVERGENT_DUPLICATE'::text AS action
                FROM authorized_incoming ai
                JOIN existing_divergent_payment_ids d USING (payment_id)
            )
            SELECT ordinal, action FROM payload_unauthorized_actions
            UNION ALL
            SELECT ordinal, action FROM existing_unauthorized_actions
            UNION ALL
            SELECT ordinal, action FROM same_batch_divergent_actions
            UNION ALL
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

    PaymentTransactionPersistenceResult storeAndClassify(
            List<AuthenticatedPaymentRequest> paymentRequests
    ) {
        if (paymentRequests.isEmpty()) {
            return new PaymentTransactionPersistenceResult(List.of(), List.of(), List.of());
        }

        BatchLocalPaymentClassification batchLocalClassification =
                classifyPaymentRequestsWithinBatch(paymentRequests);
        Map<Integer, AuthenticatedPaymentRequest> requestsByOrdinal =
                requestsByOrdinal(paymentRequests);
        List<PaymentTransactionCommand> acceptanceRequests = new ArrayList<>();
        Set<Integer> divergentDuplicateOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedRequestOrdinals = new LinkedHashSet<>();

        for (PersistenceActionRow actionRow : persistAndClassify(batchLocalClassification.incomingRows())) {
            AuthenticatedPaymentRequest paymentRequest = requestsByOrdinal.get(actionRow.ordinal());
            if (paymentRequest == null) {
                throw new IllegalStateException("Unknown payment request ordinal: " + actionRow.ordinal());
            }

            switch (actionRow.action()) {
                case ACCEPTANCE_REQUEST -> acceptanceRequests.add(paymentRequest.command());
                case DIVERGENT_DUPLICATE -> addExpandedOrdinals(
                        divergentDuplicateOrdinals,
                        batchLocalClassification.originalOrdinalsByRepresentative(),
                        actionRow.ordinal()
                );
                case UNAUTHORIZED_PSP -> addExpandedOrdinals(
                        unauthorizedRequestOrdinals,
                        batchLocalClassification.originalOrdinalsByRepresentative(),
                        actionRow.ordinal()
                );
                default -> throw new IllegalStateException("Unknown payment persistence action: " + actionRow.action());
            }
        }

        return new PaymentTransactionPersistenceResult(
                acceptanceRequests,
                requestsWithOrdinals(paymentRequests, divergentDuplicateOrdinals),
                requestsWithOrdinals(paymentRequests, unauthorizedRequestOrdinals)
        );
    }

    private Map<Integer, AuthenticatedPaymentRequest> requestsByOrdinal(
            List<AuthenticatedPaymentRequest> paymentRequests
    ) {
        Map<Integer, AuthenticatedPaymentRequest> requestsByOrdinal =
                new LinkedHashMap<>(mapCapacity(paymentRequests.size()));
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            if (requestsByOrdinal.put(paymentRequest.sourceOrdinal(), paymentRequest) != null) {
                throw new IllegalArgumentException(
                        "Payment request source ordinals must be unique: " + paymentRequest.sourceOrdinal());
            }
        }
        return requestsByOrdinal;
    }

    private void addExpandedOrdinals(
            Set<Integer> classifiedOrdinals,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative,
            int representativeOrdinal
    ) {
        List<Integer> originalOrdinals = originalOrdinalsByRepresentative.get(representativeOrdinal);
        if (originalOrdinals == null) {
            throw new IllegalStateException("Unknown payment request representative ordinal: " + representativeOrdinal);
        }
        classifiedOrdinals.addAll(originalOrdinals);
    }

    private List<AuthenticatedPaymentRequest> requestsWithOrdinals(
            List<AuthenticatedPaymentRequest> paymentRequests,
            Set<Integer> classifiedOrdinals
    ) {
        List<AuthenticatedPaymentRequest> classifiedRequests = new ArrayList<>(classifiedOrdinals.size());
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            if (classifiedOrdinals.contains(paymentRequest.sourceOrdinal())) {
                classifiedRequests.add(paymentRequest);
            }
        }
        return classifiedRequests;
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
            Array authenticatedIspbArray = null;
            try {
                ordinalArray = connection.createArrayOf("int4", incoming.ordinals());
                paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
                amountCentsArray = connection.createArrayOf("int8", incoming.amountCents());
                statusArray = connection.createArrayOf("text", incoming.statuses());
                senderBankCodeArray = connection.createArrayOf("text", incoming.senderBankCodes());
                receiverBankCodeArray = connection.createArrayOf("text", incoming.receiverBankCodes());
                requestFingerprintArray = connection.createArrayOf("text", incoming.requestFingerprints());
                requestFingerprintVersionArray = connection.createArrayOf("text", incoming.requestFingerprintVersions());
                authenticatedIspbArray = connection.createArrayOf("text", incoming.authenticatedIspbs());

                try (var statement = connection.prepareStatement(PERSISTENCE_SQL)) {
                    statement.setArray(1, ordinalArray);
                    statement.setArray(2, paymentIdArray);
                    statement.setArray(3, amountCentsArray);
                    statement.setArray(4, statusArray);
                    statement.setArray(5, senderBankCodeArray);
                    statement.setArray(6, receiverBankCodeArray);
                    statement.setArray(7, requestFingerprintArray);
                    statement.setArray(8, requestFingerprintVersionArray);
                    statement.setArray(9, authenticatedIspbArray);
                    statement.setString(10, PaymentStatus.WAITING_ACCEPTANCE.name());

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
                        requestFingerprintVersionArray,
                        authenticatedIspbArray
                );
            }
        });
    }

    private BatchLocalPaymentClassification classifyPaymentRequestsWithinBatch(
            List<AuthenticatedPaymentRequest> paymentRequests
    ) {
        Map<String, List<IncomingPaymentRow>> rowsByPaymentId =
                new LinkedHashMap<>(mapCapacity(paymentRequests.size()));
        List<IncomingPaymentRow> allIncomingRows = incomingRows(paymentRequests);
        for (IncomingPaymentRow incomingRow : allIncomingRows) {
            rowsByPaymentId.computeIfAbsent(
                    incomingRow.paymentRequest().command().getPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(incomingRow);
        }

        List<IncomingPaymentRow> rowsToClassify = new ArrayList<>(rowsByPaymentId.size());
        Map<Integer, List<Integer>> originalOrdinalsByRepresentative =
                new LinkedHashMap<>(mapCapacity(paymentRequests.size()));

        for (List<IncomingPaymentRow> paymentRows : rowsByPaymentId.values()) {
            List<Integer> originalOrdinals = new ArrayList<>(paymentRows.size());
            IncomingPaymentRow firstRow = paymentRows.get(0);
            boolean homogeneous = true;
            for (IncomingPaymentRow paymentRow : paymentRows) {
                originalOrdinals.add(paymentRow.ordinal());
                if (!sameSecurityAndFingerprint(firstRow, paymentRow)) {
                    homogeneous = false;
                }
            }

            if (homogeneous) {
                rowsToClassify.add(firstRow);
                originalOrdinalsByRepresentative.put(firstRow.ordinal(), originalOrdinals);
            } else {
                for (IncomingPaymentRow paymentRow : paymentRows) {
                    rowsToClassify.add(paymentRow);
                    originalOrdinalsByRepresentative.put(
                            paymentRow.ordinal(),
                            List.of(paymentRow.ordinal())
                    );
                }
            }
        }

        return new BatchLocalPaymentClassification(
                rowsToClassify,
                originalOrdinalsByRepresentative
        );
    }

    private boolean sameSecurityAndFingerprint(IncomingPaymentRow firstRow, IncomingPaymentRow row) {
        return Objects.equals(
                firstRow.paymentRequest().authenticatedIspb(),
                row.paymentRequest().authenticatedIspb()
        )
                && Objects.equals(firstRow.requestFingerprintVersion(), row.requestFingerprintVersion())
                && Objects.equals(firstRow.requestFingerprint(), row.requestFingerprint());
    }

    private List<IncomingPaymentRow> incomingRows(List<AuthenticatedPaymentRequest> paymentRequests) {
        List<IncomingPaymentRow> incomingRows = new ArrayList<>(paymentRequests.size());
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            PaymentTransactionCommand paymentTransaction = paymentRequest.command();
            incomingRows.add(new IncomingPaymentRow(
                    paymentRequest,
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
        String[] authenticatedIspbs = new String[size];

        for (int index = 0; index < incomingRows.size(); index++) {
            IncomingPaymentRow incomingRow = incomingRows.get(index);
            AuthenticatedPaymentRequest paymentRequest = incomingRow.paymentRequest();
            PaymentTransactionCommand paymentTransaction = paymentRequest.command();
            ordinals[index] = incomingRow.ordinal();
            paymentIds[index] = paymentTransaction.getPaymentId();
            amountCents[index] = paymentTransaction.getAmountCents();
            statuses[index] = PaymentStatus.WAITING_ACCEPTANCE.name();
            senderBankCodes[index] = Utils.getBankCode(paymentTransaction.getSender());
            receiverBankCodes[index] = Utils.getBankCode(paymentTransaction.getReceiver());
            requestFingerprints[index] = incomingRow.requestFingerprint();
            requestFingerprintVersions[index] = incomingRow.requestFingerprintVersion();
            authenticatedIspbs[index] = paymentRequest.authenticatedIspb();
        }

        return new IncomingPaymentArrays(
                ordinals,
                paymentIds,
                amountCents,
                statuses,
                senderBankCodes,
                receiverBankCodes,
                requestFingerprints,
                requestFingerprintVersions,
                authenticatedIspbs
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
            AuthenticatedPaymentRequest paymentRequest,
            String requestFingerprint,
            String requestFingerprintVersion
    ) {
        private int ordinal() {
            return paymentRequest.sourceOrdinal();
        }
    }

    private record PersistenceActionRow(int ordinal, String action) {
    }

    private record BatchLocalPaymentClassification(
            List<IncomingPaymentRow> incomingRows,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative
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
            String[] requestFingerprintVersions,
            String[] authenticatedIspbs
    ) {
        private IncomingPaymentArrays {
            int size = ordinals.length;
            if (paymentIds.length != size
                    || amountCents.length != size
                    || statuses.length != size
                    || senderBankCodes.length != size
                    || receiverBankCodes.length != size
                    || requestFingerprints.length != size
                    || requestFingerprintVersions.length != size
                    || authenticatedIspbs.length != size) {
                throw new IllegalStateException("Incoming payment arrays must have the same size");
            }
        }
    }
}
