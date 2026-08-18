package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

class IncomingPaymentRequestPersistence {

    private static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    private static final String DIVERGENT_DUPLICATE = "DIVERGENT_DUPLICATE";
    private static final String UNAUTHORIZED_PSP = "UNAUTHORIZED_PSP";
    private static final String RECHECK_EXISTING = "RECHECK_EXISTING";
    private static final String IDENTICAL_REPLAY = "IDENTICAL_REPLAY";

    private static final String RECHECK_EXISTING_SQL = """
            WITH incoming AS (
                SELECT *
                FROM unnest(
                    ?::int[],
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::text[]
                ) AS i(
                    ordinal,
                    payment_id,
                    request_fingerprint,
                    request_fingerprint_version,
                    authenticated_ispb
                )
            )
            SELECT
                i.ordinal,
                CASE
                    WHEN i.authenticated_ispb IS DISTINCT FROM p.sender_bank_code
                        THEN 'UNAUTHORIZED_PSP'
                    WHEN i.request_fingerprint_version IS DISTINCT FROM p.request_fingerprint_version
                      OR i.request_fingerprint IS DISTINCT FROM p.request_fingerprint
                        THEN 'DIVERGENT_DUPLICATE'
                    ELSE 'IDENTICAL_REPLAY'
                END AS action
            FROM incoming i
            JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
            ORDER BY i.ordinal
            """;

    private static final String LOCK_BALANCES_SQL = """
            SELECT bank_code, balance_cents
            FROM participant_balance_entity
            WHERE bank_code = ANY (?::text[])
            ORDER BY bank_code
            FOR UPDATE
            """;

    private static final String APPLY_DEBITS_SQL = """
            UPDATE participant_balance_entity balance
            SET balance_cents = balance.balance_cents - debit.amount_cents
            FROM unnest(?::text[], ?::bigint[]) AS debit(bank_code, amount_cents)
            WHERE balance.bank_code = debit.bank_code
              AND balance.balance_cents >= debit.amount_cents
            """;

    private static final String REJECT_INSUFFICIENT_SQL = """
            UPDATE payment_transaction_entity
            SET status = ?, rejection_reason = ?
            WHERE payment_id = ANY (?::text[])
              AND status = ?
            """;

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
                SELECT i.ordinal, 'PAYMENT_CREATED'::text AS action
                FROM logical_incoming i
                JOIN inserted ins ON ins.payment_id = i.payment_id
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
            ),
            conflict_loser_actions AS (
                SELECT li.ordinal, 'RECHECK_EXISTING'::text AS action
                FROM logical_incoming li
                LEFT JOIN inserted ins ON ins.payment_id = li.payment_id
                WHERE li.existing_payment_id IS NULL
                  AND ins.payment_id IS NULL
            )
            SELECT ordinal, action FROM payload_unauthorized_actions
            UNION ALL
            SELECT ordinal, action FROM existing_unauthorized_actions
            UNION ALL
            SELECT ordinal, action FROM same_batch_divergent_actions
            UNION ALL
            SELECT ordinal, action FROM inserted_actions
            UNION ALL
            SELECT ordinal, action FROM existing_divergent_actions
            UNION ALL
            SELECT ordinal, action FROM conflict_loser_actions
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
            return new PaymentTransactionPersistenceResult(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        BatchLocalPaymentClassification batchLocalClassification =
                classifyPaymentRequestsWithinBatch(paymentRequests);
        Map<Integer, AuthenticatedPaymentRequest> requestsByOrdinal =
                requestsByOrdinal(paymentRequests);
        List<AuthenticatedPaymentRequest> createdRequests = new ArrayList<>();
        List<AuthenticatedPaymentRequest> conflictLoserRequests = new ArrayList<>();
        Set<Integer> divergentDuplicateOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedRequestOrdinals = new LinkedHashSet<>();

        for (PersistenceActionRow actionRow : persistAndClassify(batchLocalClassification.incomingRows())) {
            AuthenticatedPaymentRequest paymentRequest = requestsByOrdinal.get(actionRow.ordinal());
            if (paymentRequest == null) {
                throw new IllegalStateException("Unknown payment request ordinal: " + actionRow.ordinal());
            }

            switch (actionRow.action()) {
                case PAYMENT_CREATED -> createdRequests.add(paymentRequest);
                case RECHECK_EXISTING -> conflictLoserRequests.add(paymentRequest);
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

        for (PersistenceActionRow actionRow : recheckExisting(conflictLoserRequests)) {
            switch (actionRow.action()) {
                case IDENTICAL_REPLAY -> {
                    // A concurrent transaction already created the same logical payment.
                }
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
                default -> throw new IllegalStateException("Unknown payment recheck action: " + actionRow.action());
            }
        }

        List<PaymentTransactionCommand> acceptanceRequests = new ArrayList<>();
        List<PaymentRejection> rejectedPayments = new ArrayList<>();
        for (ReservationOutcome outcome : reserveCreatedPayments(createdRequests)) {
            PaymentTransactionCommand payment = outcome.paymentRequest().command();
            if (outcome.reserved()) {
                acceptanceRequests.add(payment);
            } else {
                rejectedPayments.add(new PaymentRejection(
                        payment,
                        PaymentRejectionReason.INSUFFICIENT_FUNDS
                ));
            }
        }

        List<PaymentTransactionCommand> createdPayments = createdRequests.stream()
                .map(AuthenticatedPaymentRequest::command)
                .toList();

        return new PaymentTransactionPersistenceResult(
                acceptanceRequests,
                createdPayments,
                rejectedPayments,
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

    private List<PersistenceActionRow> recheckExisting(
            List<AuthenticatedPaymentRequest> conflictLoserRequests
    ) {
        if (conflictLoserRequests.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.execute((ConnectionCallback<List<PersistenceActionRow>>) connection -> {
            IncomingPaymentArrays incoming = incomingPaymentArrays(incomingRows(conflictLoserRequests));
            Array ordinalArray = null;
            Array paymentIdArray = null;
            Array requestFingerprintArray = null;
            Array requestFingerprintVersionArray = null;
            Array authenticatedIspbArray = null;
            try {
                ordinalArray = connection.createArrayOf("int4", incoming.ordinals());
                paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
                requestFingerprintArray = connection.createArrayOf("text", incoming.requestFingerprints());
                requestFingerprintVersionArray = connection.createArrayOf(
                        "text",
                        incoming.requestFingerprintVersions()
                );
                authenticatedIspbArray = connection.createArrayOf("text", incoming.authenticatedIspbs());

                try (var statement = connection.prepareStatement(RECHECK_EXISTING_SQL)) {
                    statement.setArray(1, ordinalArray);
                    statement.setArray(2, paymentIdArray);
                    statement.setArray(3, requestFingerprintArray);
                    statement.setArray(4, requestFingerprintVersionArray);
                    statement.setArray(5, authenticatedIspbArray);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<PersistenceActionRow> actions = new ArrayList<>(conflictLoserRequests.size());
                        while (resultSet.next()) {
                            actions.add(new PersistenceActionRow(resultSet.getInt(1), resultSet.getString(2)));
                        }
                        if (actions.size() != conflictLoserRequests.size()) {
                            throw new IllegalStateException("Concurrent payment conflict could not be reclassified");
                        }
                        return actions;
                    }
                }
            } finally {
                free(
                        ordinalArray,
                        paymentIdArray,
                        requestFingerprintArray,
                        requestFingerprintVersionArray,
                        authenticatedIspbArray
                );
            }
        });
    }

    private List<ReservationOutcome> reserveCreatedPayments(
            List<AuthenticatedPaymentRequest> createdRequests
    ) {
        if (createdRequests.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.execute((ConnectionCallback<List<ReservationOutcome>>) connection -> {
            Map<String, List<AuthenticatedPaymentRequest>> requestsByPayer = new TreeMap<>();
            for (AuthenticatedPaymentRequest createdRequest : createdRequests) {
                String payerIspb = Utils.getBankCode(createdRequest.command().getSender());
                requestsByPayer.computeIfAbsent(payerIspb, ignored -> new ArrayList<>())
                        .add(createdRequest);
            }
            for (List<AuthenticatedPaymentRequest> payerRequests : requestsByPayer.values()) {
                payerRequests.sort((first, second) -> Integer.compare(
                        first.sourceOrdinal(),
                        second.sourceOrdinal()
                ));
            }

            Map<String, Long> lockedBalances = lockBalances(
                    connection,
                    requestsByPayer.keySet().toArray(String[]::new)
            );
            Map<String, Long> debitsByPayer = new TreeMap<>();
            List<String> insufficientPaymentIds = new ArrayList<>();
            List<ReservationOutcome> outcomes = new ArrayList<>(createdRequests.size());

            for (Map.Entry<String, List<AuthenticatedPaymentRequest>> payerEntry : requestsByPayer.entrySet()) {
                String payerIspb = payerEntry.getKey();
                long remainingBalance = lockedBalances.getOrDefault(payerIspb, 0L);
                for (AuthenticatedPaymentRequest paymentRequest : payerEntry.getValue()) {
                    long amountCents = paymentRequest.command().getAmountCents();
                    if (remainingBalance >= amountCents) {
                        remainingBalance = Math.subtractExact(remainingBalance, amountCents);
                        debitsByPayer.merge(payerIspb, amountCents, Math::addExact);
                        outcomes.add(new ReservationOutcome(paymentRequest, true));
                    } else {
                        insufficientPaymentIds.add(paymentRequest.command().getPaymentId());
                        outcomes.add(new ReservationOutcome(paymentRequest, false));
                    }
                }
            }

            applyDebits(connection, debitsByPayer);
            rejectInsufficientPayments(connection, insufficientPaymentIds);
            outcomes.sort((first, second) -> Integer.compare(
                    first.paymentRequest().sourceOrdinal(),
                    second.paymentRequest().sourceOrdinal()
            ));
            return outcomes;
        });
    }

    private Map<String, Long> lockBalances(Connection connection, String[] payerIspbs) throws SQLException {
        Array payerIspbArray = null;
        try {
            payerIspbArray = connection.createArrayOf("text", payerIspbs);
            try (var statement = connection.prepareStatement(LOCK_BALANCES_SQL)) {
                statement.setArray(1, payerIspbArray);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, Long> balances = new LinkedHashMap<>(mapCapacity(payerIspbs.length));
                    while (resultSet.next()) {
                        balances.put(resultSet.getString(1), resultSet.getLong(2));
                    }
                    return balances;
                }
            }
        } finally {
            free(payerIspbArray);
        }
    }

    private void applyDebits(Connection connection, Map<String, Long> debitsByPayer) throws SQLException {
        if (debitsByPayer.isEmpty()) {
            return;
        }

        Array payerIspbArray = null;
        Array debitArray = null;
        try {
            payerIspbArray = connection.createArrayOf("text", debitsByPayer.keySet().toArray(String[]::new));
            debitArray = connection.createArrayOf("int8", debitsByPayer.values().toArray(Long[]::new));
            try (var statement = connection.prepareStatement(APPLY_DEBITS_SQL)) {
                statement.setArray(1, payerIspbArray);
                statement.setArray(2, debitArray);
                int updatedRows = statement.executeUpdate();
                if (updatedRows != debitsByPayer.size()) {
                    throw new IllegalStateException("Could not apply every payer reservation");
                }
            }
        } finally {
            free(payerIspbArray, debitArray);
        }
    }

    private void rejectInsufficientPayments(
            Connection connection,
            List<String> insufficientPaymentIds
    ) throws SQLException {
        if (insufficientPaymentIds.isEmpty()) {
            return;
        }

        Array paymentIdArray = null;
        try {
            paymentIdArray = connection.createArrayOf("text", insufficientPaymentIds.toArray(String[]::new));
            try (var statement = connection.prepareStatement(REJECT_INSUFFICIENT_SQL)) {
                statement.setString(1, PaymentStatus.REJECTED.name());
                statement.setString(2, PaymentRejectionReason.INSUFFICIENT_FUNDS.name());
                statement.setArray(3, paymentIdArray);
                statement.setString(4, PaymentStatus.WAITING_ACCEPTANCE.name());
                int updatedRows = statement.executeUpdate();
                if (updatedRows != insufficientPaymentIds.size()) {
                    throw new IllegalStateException("Could not reject every insufficient payment");
                }
            }
        } finally {
            free(paymentIdArray);
        }
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

    private record ReservationOutcome(
            AuthenticatedPaymentRequest paymentRequest,
            boolean reserved
    ) {
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
