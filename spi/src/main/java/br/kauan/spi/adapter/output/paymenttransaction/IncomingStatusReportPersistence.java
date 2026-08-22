package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentStatusTransition;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

class IncomingStatusReportPersistence {

    private static final String SETTLED_PAYMENT = "SETTLED_PAYMENT";
    private static final String REJECTED_NOTIFICATION = "REJECTED_NOTIFICATION";
    private static final String DIVERGENT_STATUS_REPORT = "DIVERGENT_STATUS_REPORT";
    private static final String UNAUTHORIZED_PSP = "UNAUTHORIZED_PSP";

    private static final String LOCK_PAYMENTS_SQL = """
            WITH incoming AS (
                SELECT *
                FROM unnest(
                    ?::int[],
                    ?::text[],
                    ?::text[],
                    ?::text[]
                ) AS i(
                    ordinal,
                    payment_id,
                    requested_status,
                    authenticated_ispb
                )
            )
            SELECT
                i.ordinal,
                i.payment_id,
                i.requested_status,
                i.authenticated_ispb,
                p.status,
                p.rejection_reason,
                p.amount_cents,
                p.sender_bank_code,
                p.receiver_bank_code
            FROM incoming i
            JOIN payment_transaction_entity p ON p.payment_id = i.payment_id
            ORDER BY p.payment_id, i.ordinal
            FOR UPDATE OF p
            """;

    private static final String LOCK_BALANCES_SQL = """
            SELECT bank_code
            FROM participant_balance_entity
            WHERE bank_code = ANY (?::text[])
            ORDER BY bank_code
            FOR UPDATE
            """;

    private static final String ACQUIRE_TRANSITIONS_SQL = """
            UPDATE payment_transaction_entity
            SET status = ?::payment_status,
                rejection_reason = NULL
            WHERE payment_id = ANY (?::text[])
              AND status = ?::payment_status
            """;

    private static final String APPLY_BALANCE_DELTAS_SQL = """
            UPDATE participant_balance_entity balance
            SET balance_cents = balance.balance_cents + delta.amount_cents
            FROM unnest(?::text[], ?::bigint[]) AS delta(bank_code, amount_cents)
            WHERE balance.bank_code = delta.bank_code
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

    StatusReportPersistenceResult classifyAndApply(List<AuthenticatedStatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return new StatusReportPersistenceResult(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        BatchLocalStatusReportClassification batchLocalClassification =
                classifyStatusReportsWithinBatch(statusReports);
        Map<Integer, AuthenticatedStatusReport> reportsByOrdinal = reportsByOrdinal(statusReports);
        List<PaymentTransactionCommand> settledPayments = new ArrayList<>();
        List<PaymentRejection> rejectedPayments = new ArrayList<>();
        List<PaymentStatusTransition> appliedStatusTransitions = new ArrayList<>();
        Set<Integer> divergentStatusReportOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedStatusReportOrdinals = new LinkedHashSet<>();

        List<StatusReportActionRow> actionRows = classifyAndApplyStatusReports(
                batchLocalClassification.statusReportsToClassify()
        );
        for (StatusReportActionRow actionRow : actionRows) {
            AuthenticatedStatusReport statusReport = reportsByOrdinal.get(actionRow.ordinal());
            if (statusReport == null) {
                throw new IllegalStateException("Unknown status report ordinal: " + actionRow.ordinal());
            }

            switch (actionRow.action()) {
                case SETTLED_PAYMENT -> {
                    settledPayments.add(toPaymentTransaction(actionRow));
                    appliedStatusTransitions.add(transition(
                            actionRow,
                            PaymentStatus.ACCEPTED_AND_SETTLED
                    ));
                }
                case REJECTED_NOTIFICATION -> {
                    PaymentRejectionReason reason = rejectionReason(actionRow.rejectionReason());
                    rejectedPayments.add(new PaymentRejection(toPaymentTransaction(actionRow), reason));
                    appliedStatusTransitions.add(transition(actionRow, PaymentStatus.REJECTED, reason));
                }
                case DIVERGENT_STATUS_REPORT -> addExpandedOrdinals(
                        divergentStatusReportOrdinals,
                        batchLocalClassification.originalOrdinalsByRepresentative(),
                        actionRow.ordinal()
                );
                case UNAUTHORIZED_PSP -> addExpandedOrdinals(
                        unauthorizedStatusReportOrdinals,
                        batchLocalClassification.originalOrdinalsByRepresentative(),
                        actionRow.ordinal()
                );
                default -> throw new IllegalStateException("Unknown status report action: " + actionRow.action());
            }
        }

        return new StatusReportPersistenceResult(
                settledPayments,
                rejectedPayments,
                appliedStatusTransitions,
                reportsWithOrdinals(statusReports, divergentStatusReportOrdinals),
                reportsWithOrdinals(statusReports, unauthorizedStatusReportOrdinals)
        );
    }

    private List<StatusReportActionRow> classifyAndApplyStatusReports(
            List<StatusReportRow> statusReports
    ) {
        return jdbcTemplate.execute((ConnectionCallback<List<StatusReportActionRow>>) connection -> {
            List<LockedStatusReportRow> lockedRows = lockExistingPayments(connection, statusReports);
            Map<Integer, LockedStatusReportRow> lockedByOrdinal = new LinkedHashMap<>();
            for (LockedStatusReportRow lockedRow : lockedRows) {
                lockedByOrdinal.put(lockedRow.ordinal(), lockedRow);
            }

            List<StatusReportActionRow> actions = new ArrayList<>();
            Map<String, List<LockedStatusReportRow>> authorizedByPaymentId = new LinkedHashMap<>();
            for (StatusReportRow statusReport : statusReports) {
                LockedStatusReportRow lockedRow = lockedByOrdinal.get(statusReport.ordinal());
                if (lockedRow == null) {
                    actions.add(classificationAction(statusReport, DIVERGENT_STATUS_REPORT));
                } else if (!Objects.equals(lockedRow.authenticatedIspb(), lockedRow.receiverBankCode())) {
                    actions.add(classificationAction(lockedRow, UNAUTHORIZED_PSP));
                } else {
                    authorizedByPaymentId.computeIfAbsent(
                            lockedRow.paymentId(),
                            ignored -> new ArrayList<>()
                    ).add(lockedRow);
                }
            }

            List<TransitionCandidate> transitionCandidates = new ArrayList<>();
            for (List<LockedStatusReportRow> paymentRows : authorizedByPaymentId.values()) {
                if (hasConflictingStatuses(paymentRows)) {
                    for (LockedStatusReportRow paymentRow : paymentRows) {
                        actions.add(classificationAction(paymentRow, DIVERGENT_STATUS_REPORT));
                    }
                    continue;
                }

                LockedStatusReportRow paymentRow = paymentRows.get(0);
                PaymentStatus requestedStatus = paymentRow.requestedStatus();
                PaymentStatus existingStatus = paymentRow.existingStatus();
                if (requestedStatus == PaymentStatus.ACCEPTED_IN_PROCESS) {
                    if (existingStatus == PaymentStatus.WAITING_ACCEPTANCE) {
                        transitionCandidates.add(new TransitionCandidate(
                                paymentRow,
                                PaymentStatus.ACCEPTED_AND_SETTLED
                        ));
                    } else if (!acceptedReplayIsNoOp(paymentRow)) {
                        actions.add(classificationAction(paymentRow, DIVERGENT_STATUS_REPORT));
                    }
                } else if (requestedStatus == PaymentStatus.REJECTED) {
                    if (existingStatus == PaymentStatus.WAITING_ACCEPTANCE) {
                        transitionCandidates.add(new TransitionCandidate(paymentRow, PaymentStatus.REJECTED));
                    } else if (existingStatus != PaymentStatus.REJECTED) {
                        actions.add(classificationAction(paymentRow, DIVERGENT_STATUS_REPORT));
                    }
                } else {
                    actions.add(classificationAction(paymentRow, DIVERGENT_STATUS_REPORT));
                }
            }

            lockRequiredBalances(connection, transitionCandidates);
            List<AcquiredTransition> acquiredTransitions = acquireTransitions(
                    connection,
                    transitionCandidates
            );
            applyBalanceDeltas(connection, acquiredTransitions);
            for (AcquiredTransition transition : acquiredTransitions) {
                actions.add(new StatusReportActionRow(
                        transition.ordinal(),
                        transition.resultingStatus() == PaymentStatus.ACCEPTED_AND_SETTLED
                                ? SETTLED_PAYMENT
                                : REJECTED_NOTIFICATION,
                        transition.paymentId(),
                        transition.amountCents(),
                        transition.senderBankCode(),
                        transition.receiverBankCode(),
                        transition.rejectionReason()
                ));
            }

            actions.sort((first, second) -> Integer.compare(first.ordinal(), second.ordinal()));
            return actions;
        });
    }

    private boolean hasConflictingStatuses(List<LockedStatusReportRow> paymentRows) {
        PaymentStatus firstStatus = paymentRows.get(0).requestedStatus();
        for (LockedStatusReportRow paymentRow : paymentRows) {
            if (paymentRow.requestedStatus() != firstStatus) {
                return true;
            }
        }
        return false;
    }

    private boolean acceptedReplayIsNoOp(LockedStatusReportRow paymentRow) {
        if (paymentRow.existingStatus() == PaymentStatus.ACCEPTED_IN_PROCESS
                || paymentRow.existingStatus() == PaymentStatus.ACCEPTED_AND_SETTLED) {
            return true;
        }
        return paymentRow.existingStatus() == PaymentStatus.REJECTED
                && paymentRow.existingRejectionReason() == PaymentRejectionReason.INSUFFICIENT_FUNDS;
    }

    private List<LockedStatusReportRow> lockExistingPayments(
            Connection connection,
            List<StatusReportRow> statusReports
    ) throws SQLException {
        IncomingStatusReportArrays incoming = incomingStatusReportArrays(statusReports);
        Array ordinalArray = null;
        Array paymentIdArray = null;
        Array requestedStatusArray = null;
        Array authenticatedIspbArray = null;
        try {
            ordinalArray = connection.createArrayOf("int4", incoming.ordinals());
            paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
            requestedStatusArray = connection.createArrayOf("text", incoming.requestedStatuses());
            authenticatedIspbArray = connection.createArrayOf("text", incoming.authenticatedIspbs());
            try (var statement = connection.prepareStatement(LOCK_PAYMENTS_SQL)) {
                statement.setArray(1, ordinalArray);
                statement.setArray(2, paymentIdArray);
                statement.setArray(3, requestedStatusArray);
                statement.setArray(4, authenticatedIspbArray);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<LockedStatusReportRow> rows = new ArrayList<>(statusReports.size());
                    while (resultSet.next()) {
                        rows.add(new LockedStatusReportRow(
                                resultSet.getInt(1),
                                resultSet.getString(2),
                                PaymentStatus.valueOf(resultSet.getString(3)),
                                resultSet.getString(4),
                                PaymentStatus.valueOf(resultSet.getString(5)),
                                rejectionReason(resultSet.getString(6)),
                                resultSet.getLong(7),
                                resultSet.getString(8),
                                resultSet.getString(9)
                        ));
                    }
                    return rows;
                }
            }
        } finally {
            free(ordinalArray, paymentIdArray, requestedStatusArray, authenticatedIspbArray);
        }
    }

    private void lockRequiredBalances(
            Connection connection,
            List<TransitionCandidate> transitionCandidates
    ) throws SQLException {
        Set<String> requiredIspbs = new TreeSet<>();
        for (TransitionCandidate candidate : transitionCandidates) {
            requiredIspbs.add(candidate.resultingStatus() == PaymentStatus.ACCEPTED_AND_SETTLED
                    ? candidate.payment().receiverBankCode()
                    : candidate.payment().senderBankCode());
        }
        if (requiredIspbs.isEmpty()) {
            return;
        }

        Array ispbArray = null;
        try {
            ispbArray = connection.createArrayOf("text", requiredIspbs.toArray(String[]::new));
            try (var statement = connection.prepareStatement(LOCK_BALANCES_SQL)) {
                statement.setArray(1, ispbArray);
                int lockedBalances = 0;
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        lockedBalances++;
                    }
                }
                if (lockedBalances != requiredIspbs.size()) {
                    throw new IllegalStateException("Required participant balance is missing");
                }
            }
        } finally {
            free(ispbArray);
        }
    }

    private List<AcquiredTransition> acquireTransitions(
            Connection connection,
            List<TransitionCandidate> transitionCandidates
    ) throws SQLException {
        if (transitionCandidates.isEmpty()) {
            return List.of();
        }

        Map<PaymentStatus, List<TransitionCandidate>> candidatesByResultingStatus =
                new EnumMap<>(PaymentStatus.class);
        for (TransitionCandidate candidate : transitionCandidates) {
            candidatesByResultingStatus.computeIfAbsent(
                    candidate.resultingStatus(),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }

        List<AcquiredTransition> acquired = new ArrayList<>(transitionCandidates.size());
        for (Map.Entry<PaymentStatus, List<TransitionCandidate>> entry
                : candidatesByResultingStatus.entrySet()) {
            List<TransitionCandidate> candidates = entry.getValue();
            acquireTransitionsForStatus(connection, entry.getKey(), candidates);
            for (TransitionCandidate candidate : candidates) {
                LockedStatusReportRow payment = candidate.payment();
                acquired.add(new AcquiredTransition(
                        payment.ordinal(),
                        payment.paymentId(),
                        candidate.resultingStatus(),
                        payment.amountCents(),
                        payment.senderBankCode(),
                        payment.receiverBankCode(),
                        null
                ));
            }
        }
        acquired.sort((first, second) -> Integer.compare(first.ordinal(), second.ordinal()));
        return acquired;
    }

    private void acquireTransitionsForStatus(
            Connection connection,
            PaymentStatus resultingStatus,
            List<TransitionCandidate> transitionCandidates
    ) throws SQLException {
        String[] paymentIds = new String[transitionCandidates.size()];
        for (int index = 0; index < transitionCandidates.size(); index++) {
            paymentIds[index] = transitionCandidates.get(index).payment().paymentId();
        }

        Array paymentIdArray = null;
        try {
            paymentIdArray = connection.createArrayOf("text", paymentIds);
            try (var statement = connection.prepareStatement(ACQUIRE_TRANSITIONS_SQL)) {
                statement.setString(1, resultingStatus.name());
                statement.setArray(2, paymentIdArray);
                statement.setString(3, PaymentStatus.WAITING_ACCEPTANCE.name());
                int acquired = statement.executeUpdate();
                if (acquired != transitionCandidates.size()) {
                    throw new IllegalStateException("Could not acquire every waiting payment transition");
                }
            }
        } finally {
            free(paymentIdArray);
        }
    }

    private void applyBalanceDeltas(
            Connection connection,
            List<AcquiredTransition> acquiredTransitions
    ) throws SQLException {
        Map<String, Long> deltasByParticipant = new TreeMap<>();
        for (AcquiredTransition transition : acquiredTransitions) {
            String participantIspb = transition.resultingStatus() == PaymentStatus.ACCEPTED_AND_SETTLED
                    ? transition.receiverBankCode()
                    : transition.senderBankCode();
            deltasByParticipant.merge(participantIspb, transition.amountCents(), Math::addExact);
        }
        if (deltasByParticipant.isEmpty()) {
            return;
        }

        Array ispbArray = null;
        Array deltaArray = null;
        try {
            ispbArray = connection.createArrayOf("text", deltasByParticipant.keySet().toArray(String[]::new));
            deltaArray = connection.createArrayOf("int8", deltasByParticipant.values().toArray(Long[]::new));
            try (var statement = connection.prepareStatement(APPLY_BALANCE_DELTAS_SQL)) {
                statement.setArray(1, ispbArray);
                statement.setArray(2, deltaArray);
                int updatedBalances = statement.executeUpdate();
                if (updatedBalances != deltasByParticipant.size()) {
                    throw new IllegalStateException("Could not apply every participant balance delta");
                }
            }
        } finally {
            free(ispbArray, deltaArray);
        }
    }

    private StatusReportActionRow classificationAction(
            StatusReportRow statusReport,
            String action
    ) {
        return new StatusReportActionRow(
                statusReport.ordinal(),
                action,
                statusReport.statusReport().command().getOriginalPaymentId(),
                null,
                null,
                null,
                null
        );
    }

    private StatusReportActionRow classificationAction(
            LockedStatusReportRow statusReport,
            String action
    ) {
        return new StatusReportActionRow(
                statusReport.ordinal(),
                action,
                statusReport.paymentId(),
                null,
                null,
                null,
                null
        );
    }

    private PaymentStatusTransition transition(
            StatusReportActionRow actionRow,
            PaymentStatus resultingStatus
    ) {
        return transition(actionRow, resultingStatus, null);
    }

    private PaymentStatusTransition transition(
            StatusReportActionRow actionRow,
            PaymentStatus resultingStatus,
            PaymentRejectionReason rejectionReason
    ) {
        return new PaymentStatusTransition(
                actionRow.paymentId(),
                PaymentStatus.WAITING_ACCEPTANCE,
                resultingStatus,
                rejectionReason
        );
    }

    private PaymentRejectionReason rejectionReason(String rejectionReason) {
        return rejectionReason == null ? null : PaymentRejectionReason.valueOf(rejectionReason);
    }

    private Map<Integer, AuthenticatedStatusReport> reportsByOrdinal(
            List<AuthenticatedStatusReport> statusReports
    ) {
        Map<Integer, AuthenticatedStatusReport> reportsByOrdinal =
                new LinkedHashMap<>(mapCapacity(statusReports.size()));
        for (AuthenticatedStatusReport statusReport : statusReports) {
            if (reportsByOrdinal.put(statusReport.sourceOrdinal(), statusReport) != null) {
                throw new IllegalArgumentException(
                        "Status report source ordinals must be unique: " + statusReport.sourceOrdinal());
            }
        }
        return reportsByOrdinal;
    }

    private void addExpandedOrdinals(
            Set<Integer> classifiedOrdinals,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative,
            int representativeOrdinal
    ) {
        List<Integer> originalOrdinals = originalOrdinalsByRepresentative.get(representativeOrdinal);
        if (originalOrdinals == null) {
            throw new IllegalStateException("Unknown status report representative ordinal: " + representativeOrdinal);
        }
        classifiedOrdinals.addAll(originalOrdinals);
    }

    private BatchLocalStatusReportClassification classifyStatusReportsWithinBatch(
            List<AuthenticatedStatusReport> statusReports
    ) {
        Map<String, List<StatusReportRow>> rowsByPaymentId =
                new LinkedHashMap<>(mapCapacity(statusReports.size()));
        for (AuthenticatedStatusReport statusReport : statusReports) {
            rowsByPaymentId.computeIfAbsent(
                    statusReport.command().getOriginalPaymentId(),
                    ignored -> new ArrayList<>()
            ).add(new StatusReportRow(statusReport));
        }

        List<StatusReportRow> statusReportsToClassify = new ArrayList<>(rowsByPaymentId.size());
        Map<Integer, List<Integer>> originalOrdinalsByRepresentative =
                new LinkedHashMap<>(mapCapacity(statusReports.size()));

        for (List<StatusReportRow> statusReportRows : rowsByPaymentId.values()) {
            List<Integer> originalOrdinals = new ArrayList<>(statusReportRows.size());
            StatusReportRow firstRow = statusReportRows.get(0);
            boolean homogeneous = true;
            for (StatusReportRow statusReportRow : statusReportRows) {
                originalOrdinals.add(statusReportRow.ordinal());
                if (!sameSecurityAndStatus(firstRow, statusReportRow)) {
                    homogeneous = false;
                }
            }

            if (homogeneous) {
                statusReportsToClassify.add(firstRow);
                originalOrdinalsByRepresentative.put(firstRow.ordinal(), originalOrdinals);
            } else {
                for (StatusReportRow statusReportRow : statusReportRows) {
                    statusReportsToClassify.add(statusReportRow);
                    originalOrdinalsByRepresentative.put(
                            statusReportRow.ordinal(),
                            List.of(statusReportRow.ordinal())
                    );
                }
            }
        }

        return new BatchLocalStatusReportClassification(
                statusReportsToClassify,
                originalOrdinalsByRepresentative
        );
    }

    private boolean sameSecurityAndStatus(StatusReportRow firstRow, StatusReportRow row) {
        return Objects.equals(
                firstRow.statusReport().authenticatedIspb(),
                row.statusReport().authenticatedIspb()
        )
                && firstRow.statusReport().command().getStatus() == row.statusReport().command().getStatus();
    }

    private List<AuthenticatedStatusReport> reportsWithOrdinals(
            List<AuthenticatedStatusReport> statusReports,
            Set<Integer> classifiedOrdinals
    ) {
        List<AuthenticatedStatusReport> classifiedReports = new ArrayList<>(classifiedOrdinals.size());
        for (AuthenticatedStatusReport statusReport : statusReports) {
            if (classifiedOrdinals.contains(statusReport.sourceOrdinal())) {
                classifiedReports.add(statusReport);
            }
        }
        return classifiedReports;
    }

    private IncomingStatusReportArrays incomingStatusReportArrays(List<StatusReportRow> statusReports) {
        int size = statusReports.size();
        Integer[] ordinals = new Integer[size];
        String[] paymentIds = new String[size];
        String[] requestedStatuses = new String[size];
        String[] authenticatedIspbs = new String[size];

        for (int index = 0; index < statusReports.size(); index++) {
            StatusReportRow statusReportRow = statusReports.get(index);
            AuthenticatedStatusReport authenticatedStatusReport = statusReportRow.statusReport();
            StatusReportCommand statusReport = authenticatedStatusReport.command();
            ordinals[index] = statusReportRow.ordinal();
            paymentIds[index] = statusReport.getOriginalPaymentId();
            requestedStatuses[index] = statusReport.getStatus().name();
            authenticatedIspbs[index] = authenticatedStatusReport.authenticatedIspb();
        }

        return new IncomingStatusReportArrays(
                ordinals,
                paymentIds,
                requestedStatuses,
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

    private PaymentTransactionCommand toPaymentTransaction(StatusReportActionRow actionRow) {
        Entity entity = new Entity();
        entity.setPaymentId(actionRow.paymentId());
        entity.setAmountCents(actionRow.amountCents());
        entity.setSenderBankCode(actionRow.senderBankCode());
        entity.setReceiverBankCode(actionRow.receiverBankCode());
        return repositoryMapper.toDomain(entity);
    }

    private record StatusReportRow(AuthenticatedStatusReport statusReport) {
        private int ordinal() {
            return statusReport.sourceOrdinal();
        }
    }

    private record LockedStatusReportRow(
            int ordinal,
            String paymentId,
            PaymentStatus requestedStatus,
            String authenticatedIspb,
            PaymentStatus existingStatus,
            PaymentRejectionReason existingRejectionReason,
            long amountCents,
            String senderBankCode,
            String receiverBankCode
    ) {
    }

    private record TransitionCandidate(
            LockedStatusReportRow payment,
            PaymentStatus resultingStatus
    ) {
    }

    private record AcquiredTransition(
            int ordinal,
            String paymentId,
            PaymentStatus resultingStatus,
            long amountCents,
            String senderBankCode,
            String receiverBankCode,
            String rejectionReason
    ) {
    }

    private record BatchLocalStatusReportClassification(
            List<StatusReportRow> statusReportsToClassify,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative
    ) {
    }

    private record StatusReportActionRow(
            int ordinal,
            String action,
            String paymentId,
            Long amountCents,
            String senderBankCode,
            String receiverBankCode,
            String rejectionReason
    ) {
    }

    private record IncomingStatusReportArrays(
            Integer[] ordinals,
            String[] paymentIds,
            String[] requestedStatuses,
            String[] authenticatedIspbs
    ) {
        private IncomingStatusReportArrays {
            int size = ordinals.length;
            if (paymentIds.length != size
                    || requestedStatuses.length != size
                    || authenticatedIspbs.length != size) {
                throw new IllegalStateException("Incoming status report arrays must have the same size");
            }
        }
    }
}
