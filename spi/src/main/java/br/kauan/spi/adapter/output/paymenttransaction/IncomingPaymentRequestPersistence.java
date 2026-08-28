package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentState;
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

    private static final String SELECT_CONFLICTS_SQL = """
            SELECT
                payment_id,
                sender_bank_code,
                request_fingerprint,
                request_fingerprint_version
            FROM payment_transaction_entity
            WHERE payment_id = ANY (?::text[])
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
            SET state = ?::payment_state,
                rejection_cause = ?::payment_rejection_cause,
                external_reason_codes = NULL
            WHERE payment_id = ANY (?::text[])
              AND state = ?::payment_state
            """;

    private static final String INSERT_CANDIDATES_SQL = """
            INSERT INTO payment_transaction_entity (
                payment_id,
                amount_cents,
                state,
                sender_bank_code,
                receiver_bank_code,
                request_fingerprint,
                request_fingerprint_version
            )
            SELECT
                payment_id,
                amount_cents,
                ?::payment_state,
                sender_bank_code,
                receiver_bank_code,
                request_fingerprint,
                request_fingerprint_version
            FROM unnest(
                ?::text[],
                ?::bigint[],
                ?::text[],
                ?::text[],
                ?::bytea[],
                ?::smallint[]
            ) AS incoming(
                    payment_id,
                    amount_cents,
                    sender_bank_code,
                    receiver_bank_code,
                    request_fingerprint,
                    request_fingerprint_version
            )
            ON CONFLICT (payment_id) DO NOTHING
            RETURNING payment_id
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

        validateUniqueSourceOrdinals(paymentRequests);
        BatchLocalPaymentClassification batchLocalClassification =
                classifyPaymentRequestsWithinBatch(paymentRequests);
        List<AuthenticatedPaymentRequest> createdRequests = new ArrayList<>();
        List<IncomingPaymentRow> conflictRows = new ArrayList<>();
        Set<Integer> divergentDuplicateOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedRequestOrdinals =
                new LinkedHashSet<>(batchLocalClassification.unauthorizedRequestOrdinals());

        classifyNonHomogeneousGroups(
                batchLocalClassification.nonHomogeneousGroups(),
                divergentDuplicateOrdinals,
                unauthorizedRequestOrdinals
        );

        Set<String> createdPaymentIds = insertCandidates(batchLocalClassification.insertionCandidates());
        for (IncomingPaymentRow candidate : batchLocalClassification.insertionCandidates()) {
            String paymentId = candidate.paymentRequest().command().getPaymentId();
            if (createdPaymentIds.remove(paymentId)) {
                createdRequests.add(candidate.paymentRequest());
            } else {
                conflictRows.add(candidate);
            }
        }
        if (!createdPaymentIds.isEmpty()) {
            throw new IllegalStateException("Insert returned unknown payment IDs: " + createdPaymentIds);
        }

        Map<String, ExistingPaymentRow> existingPayments = selectConflicts(conflictRows);
        for (IncomingPaymentRow conflictRow : conflictRows) {
            String paymentId = conflictRow.paymentRequest().command().getPaymentId();
            ExistingPaymentRow existingPayment = existingPayments.get(paymentId);
            if (existingPayment == null) {
                throw new IllegalStateException("Payment conflict could not be reclassified: " + paymentId);
            }

            if (!Objects.equals(
                    conflictRow.paymentRequest().authenticatedIspb(),
                    existingPayment.senderBankCode()
            )) {
                addExpandedOrdinals(
                        unauthorizedRequestOrdinals,
                        batchLocalClassification.originalOrdinalsByRepresentative(),
                        conflictRow.ordinal()
                );
            } else if (!sameFingerprint(conflictRow, existingPayment)) {
                addExpandedOrdinals(
                        divergentDuplicateOrdinals,
                        batchLocalClassification.originalOrdinalsByRepresentative(),
                        conflictRow.ordinal()
                );
            }
        }

        createdRequests.sort((first, second) -> Integer.compare(
                first.sourceOrdinal(),
                second.sourceOrdinal()
        ));

        List<PaymentTransactionCommand> acceptanceRequests = new ArrayList<>();
        List<PaymentRejection> rejectedPayments = new ArrayList<>();
        for (ReservationOutcome outcome : reserveCreatedPayments(createdRequests)) {
            PaymentTransactionCommand payment = outcome.paymentRequest().command();
            if (outcome.reserved()) {
                acceptanceRequests.add(payment);
            } else {
                rejectedPayments.add(PaymentRejection.insufficientFunds(payment));
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

    private void validateUniqueSourceOrdinals(
            List<AuthenticatedPaymentRequest> paymentRequests
    ) {
        Set<Integer> sourceOrdinals = new LinkedHashSet<>(mapCapacity(paymentRequests.size()));
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            if (!sourceOrdinals.add(paymentRequest.sourceOrdinal())) {
                throw new IllegalArgumentException(
                        "Payment request source ordinals must be unique: " + paymentRequest.sourceOrdinal());
            }
        }
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

    private Set<String> insertCandidates(List<IncomingPaymentRow> insertionCandidates) {
        if (insertionCandidates.isEmpty()) {
            return Set.of();
        }

        return jdbcTemplate.execute((ConnectionCallback<Set<String>>) connection -> {
            IncomingPaymentArrays incoming = incomingPaymentArrays(insertionCandidates);
            Array paymentIdArray = null;
            Array amountCentsArray = null;
            Array senderBankCodeArray = null;
            Array receiverBankCodeArray = null;
            Array requestFingerprintArray = null;
            Array requestFingerprintVersionArray = null;
            try {
                paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
                amountCentsArray = connection.createArrayOf("int8", incoming.amountCents());
                senderBankCodeArray = connection.createArrayOf("text", incoming.senderBankCodes());
                receiverBankCodeArray = connection.createArrayOf("text", incoming.receiverBankCodes());
                requestFingerprintArray = connection.createArrayOf("bytea", incoming.requestFingerprints());
                requestFingerprintVersionArray = connection.createArrayOf(
                        "int2",
                        incoming.requestFingerprintVersions()
                );

                try (var statement = connection.prepareStatement(INSERT_CANDIDATES_SQL)) {
                    statement.setString(1, PaymentState.WAITING_ACCEPTANCE.name());
                    statement.setArray(2, paymentIdArray);
                    statement.setArray(3, amountCentsArray);
                    statement.setArray(4, senderBankCodeArray);
                    statement.setArray(5, receiverBankCodeArray);
                    statement.setArray(6, requestFingerprintArray);
                    statement.setArray(7, requestFingerprintVersionArray);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        Set<String> createdPaymentIds = new LinkedHashSet<>(insertionCandidates.size());
                        while (resultSet.next()) {
                            if (!createdPaymentIds.add(resultSet.getString(1))) {
                                throw new IllegalStateException("Insert returned a duplicate payment ID");
                            }
                        }
                        return createdPaymentIds;
                    }
                }
            } finally {
                free(
                        paymentIdArray,
                        amountCentsArray,
                        senderBankCodeArray,
                        receiverBankCodeArray,
                        requestFingerprintArray,
                        requestFingerprintVersionArray
                );
            }
        });
    }

    private void classifyNonHomogeneousGroups(
            List<List<IncomingPaymentRow>> nonHomogeneousGroups,
            Set<Integer> divergentDuplicateOrdinals,
            Set<Integer> unauthorizedRequestOrdinals
    ) {
        if (nonHomogeneousGroups.isEmpty()) {
            return;
        }

        Set<String> paymentIds = new LinkedHashSet<>(nonHomogeneousGroups.size());
        for (List<IncomingPaymentRow> paymentRows : nonHomogeneousGroups) {
            paymentIds.add(paymentRows.get(0).paymentRequest().command().getPaymentId());
        }
        Map<String, ExistingPaymentRow> existingPayments = selectExistingPayments(paymentIds);

        for (List<IncomingPaymentRow> paymentRows : nonHomogeneousGroups) {
            ExistingPaymentRow existingPayment = existingPayments.get(
                    paymentRows.get(0).paymentRequest().command().getPaymentId()
            );
            if (existingPayment == null) {
                for (IncomingPaymentRow paymentRow : paymentRows) {
                    divergentDuplicateOrdinals.add(paymentRow.ordinal());
                }
                continue;
            }

            List<IncomingPaymentRow> ownerRows = new ArrayList<>(paymentRows.size());
            for (IncomingPaymentRow paymentRow : paymentRows) {
                if (Objects.equals(
                        paymentRow.paymentRequest().authenticatedIspb(),
                        existingPayment.senderBankCode()
                )) {
                    ownerRows.add(paymentRow);
                } else {
                    unauthorizedRequestOrdinals.add(paymentRow.ordinal());
                }
            }
            if (ownerRows.isEmpty()) {
                continue;
            }

            IncomingPaymentRow representative = ownerRows.get(0);
            boolean ownerRowsDiverge = ownerRows.stream()
                    .anyMatch(row -> !sameFingerprint(representative, row));
            if (ownerRowsDiverge || !sameFingerprint(representative, existingPayment)) {
                for (IncomingPaymentRow ownerRow : ownerRows) {
                    divergentDuplicateOrdinals.add(ownerRow.ordinal());
                }
            }
        }
    }

    private Map<String, ExistingPaymentRow> selectConflicts(List<IncomingPaymentRow> conflictRows) {
        if (conflictRows.isEmpty()) {
            return Map.of();
        }

        Set<String> paymentIds = new LinkedHashSet<>(conflictRows.size());
        for (IncomingPaymentRow conflictRow : conflictRows) {
            paymentIds.add(conflictRow.paymentRequest().command().getPaymentId());
        }
        Map<String, ExistingPaymentRow> existingPayments = selectExistingPayments(paymentIds);
        if (existingPayments.size() != paymentIds.size()) {
            throw new IllegalStateException("Not every payment conflict could be reclassified");
        }
        return existingPayments;
    }

    private Map<String, ExistingPaymentRow> selectExistingPayments(Set<String> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }

        return jdbcTemplate.execute((ConnectionCallback<Map<String, ExistingPaymentRow>>) connection -> {
            Array paymentIdArray = null;
            try {
                paymentIdArray = connection.createArrayOf("text", paymentIds.toArray(String[]::new));

                try (var statement = connection.prepareStatement(SELECT_CONFLICTS_SQL)) {
                    statement.setArray(1, paymentIdArray);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        Map<String, ExistingPaymentRow> existingPayments =
                                new LinkedHashMap<>(mapCapacity(paymentIds.size()));
                        while (resultSet.next()) {
                            ExistingPaymentRow existingPayment = new ExistingPaymentRow(
                                    resultSet.getString("payment_id"),
                                    resultSet.getString("sender_bank_code"),
                                    resultSet.getBytes("request_fingerprint"),
                                    nullableShort(resultSet, "request_fingerprint_version")
                            );
                            if (existingPayments.put(existingPayment.paymentId(), existingPayment) != null) {
                                throw new IllegalStateException("Conflict query returned a duplicate payment ID");
                            }
                        }
                        return existingPayments;
                    }
                }
            } finally {
                free(paymentIdArray);
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
            if (lockedBalances.size() != requestsByPayer.size()) {
                throw new IllegalStateException("Required participant balance is missing");
            }
            Map<String, Long> debitsByPayer = new TreeMap<>();
            List<String> insufficientPaymentIds = new ArrayList<>();
            List<ReservationOutcome> outcomes = new ArrayList<>(createdRequests.size());

            for (Map.Entry<String, List<AuthenticatedPaymentRequest>> payerEntry : requestsByPayer.entrySet()) {
                String payerIspb = payerEntry.getKey();
                long remainingBalance = lockedBalances.get(payerIspb);
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
                statement.setString(1, PaymentState.REJECTED.name());
                statement.setString(2, PaymentRejectionCause.INSUFFICIENT_FUNDS.name());
                statement.setArray(3, paymentIdArray);
                statement.setString(4, PaymentState.WAITING_ACCEPTANCE.name());
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
        Set<Integer> unauthorizedRequestOrdinals = new LinkedHashSet<>();
        for (IncomingPaymentRow incomingRow : allIncomingRows) {
            if (!Objects.equals(
                    incomingRow.paymentRequest().authenticatedIspb(),
                    Utils.getBankCode(incomingRow.paymentRequest().command().getSender())
            )) {
                unauthorizedRequestOrdinals.add(incomingRow.ordinal());
                continue;
            }
            rowsByPaymentId.computeIfAbsent(
                    incomingRow.paymentRequest().command().getPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(incomingRow);
        }

        List<IncomingPaymentRow> insertionCandidates = new ArrayList<>(rowsByPaymentId.size());
        Map<Integer, List<Integer>> originalOrdinalsByRepresentative =
                new LinkedHashMap<>(mapCapacity(paymentRequests.size()));
        List<List<IncomingPaymentRow>> nonHomogeneousGroups = new ArrayList<>();

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
                insertionCandidates.add(firstRow);
                originalOrdinalsByRepresentative.put(firstRow.ordinal(), originalOrdinals);
            } else {
                nonHomogeneousGroups.add(paymentRows);
            }
        }

        return new BatchLocalPaymentClassification(
                insertionCandidates,
                originalOrdinalsByRepresentative,
                nonHomogeneousGroups,
                unauthorizedRequestOrdinals
        );
    }

    private boolean sameSecurityAndFingerprint(IncomingPaymentRow firstRow, IncomingPaymentRow row) {
        return Objects.equals(
                firstRow.paymentRequest().authenticatedIspb(),
                row.paymentRequest().authenticatedIspb()
        ) && sameFingerprint(firstRow, row);
    }

    private boolean sameFingerprint(IncomingPaymentRow firstRow, IncomingPaymentRow row) {
        return firstRow.requestFingerprint().equals(row.requestFingerprint());
    }

    private boolean sameFingerprint(IncomingPaymentRow incoming, ExistingPaymentRow existing) {
        return incoming.requestFingerprint().matches(
                existing.requestFingerprint(),
                existing.requestFingerprintVersion()
        );
    }

    private List<IncomingPaymentRow> incomingRows(List<AuthenticatedPaymentRequest> paymentRequests) {
        List<IncomingPaymentRow> incomingRows = new ArrayList<>(paymentRequests.size());
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            PaymentTransactionCommand paymentTransaction = paymentRequest.command();
            incomingRows.add(new IncomingPaymentRow(
                    paymentRequest,
                    RequestFingerprint.calculate(paymentTransaction)
            ));
        }
        return incomingRows;
    }

    private IncomingPaymentArrays incomingPaymentArrays(List<IncomingPaymentRow> incomingRows) {
        int size = incomingRows.size();
        String[] paymentIds = new String[size];
        Long[] amountCents = new Long[size];
        String[] senderBankCodes = new String[size];
        String[] receiverBankCodes = new String[size];
        byte[][] requestFingerprints = new byte[size][];
        Short[] requestFingerprintVersions = new Short[size];

        for (int index = 0; index < incomingRows.size(); index++) {
            IncomingPaymentRow incomingRow = incomingRows.get(index);
            AuthenticatedPaymentRequest paymentRequest = incomingRow.paymentRequest();
            PaymentTransactionCommand paymentTransaction = paymentRequest.command();
            paymentIds[index] = paymentTransaction.getPaymentId();
            amountCents[index] = paymentTransaction.getAmountCents();
            senderBankCodes[index] = Utils.getBankCode(paymentTransaction.getSender());
            receiverBankCodes[index] = Utils.getBankCode(paymentTransaction.getReceiver());
            requestFingerprints[index] = incomingRow.requestFingerprint().bytes();
            requestFingerprintVersions[index] = incomingRow.requestFingerprint().version();
        }

        return new IncomingPaymentArrays(
                paymentIds,
                amountCents,
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

    private Short nullableShort(ResultSet resultSet, String columnName) throws SQLException {
        short value = resultSet.getShort(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private int mapCapacity(int expectedSize) {
        return Math.max(16, expectedSize * 4 / 3 + 1);
    }

    private record IncomingPaymentRow(
            AuthenticatedPaymentRequest paymentRequest,
            RequestFingerprint requestFingerprint
    ) {
        private int ordinal() {
            return paymentRequest.sourceOrdinal();
        }
    }

    private record ReservationOutcome(
            AuthenticatedPaymentRequest paymentRequest,
            boolean reserved
    ) {
    }

    private record BatchLocalPaymentClassification(
            List<IncomingPaymentRow> insertionCandidates,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative,
            List<List<IncomingPaymentRow>> nonHomogeneousGroups,
            Set<Integer> unauthorizedRequestOrdinals
    ) {
    }

    private record IncomingPaymentArrays(
            String[] paymentIds,
            Long[] amountCents,
            String[] senderBankCodes,
            String[] receiverBankCodes,
            byte[][] requestFingerprints,
            Short[] requestFingerprintVersions
    ) {
        private IncomingPaymentArrays {
            int size = paymentIds.length;
            if (amountCents.length != size
                    || senderBankCodes.length != size
                    || receiverBankCodes.length != size
                    || requestFingerprints.length != size
                    || requestFingerprintVersions.length != size) {
                throw new IllegalStateException("Incoming payment arrays must have the same size");
            }
        }
    }

    private record ExistingPaymentRow(
            String paymentId,
            String senderBankCode,
            byte[] requestFingerprint,
            Short requestFingerprintVersion
    ) {
    }
}
