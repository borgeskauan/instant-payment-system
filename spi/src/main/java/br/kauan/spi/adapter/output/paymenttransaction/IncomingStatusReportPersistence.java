package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.entity.transfer.PaymentReference;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.Candidate;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.Classification;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.Decision;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.LockedPayment;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.PreparedBatch;
import br.kauan.spi.domain.services.payment.StatusTransitionPolicy.Transition;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

class IncomingStatusReportPersistence {

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
                    requested_outcome,
                    authenticated_ispb
                )
            )
            SELECT
                i.ordinal,
                i.payment_id,
                i.requested_outcome,
                i.authenticated_ispb,
                p.state,
                p.rejection_cause,
                p.external_reason_codes,
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
            SET state = ?::payment_state,
                rejection_cause = NULL,
                external_reason_codes = ?::text[]
            WHERE payment_id = ANY (?::text[])
              AND state = ?::payment_state
            """;

    private static final String APPLY_BALANCE_DELTAS_SQL = """
            UPDATE participant_balance_entity balance
            SET balance_cents = balance.balance_cents + delta.amount_cents
            FROM unnest(?::text[], ?::bigint[]) AS delta(bank_code, amount_cents)
            WHERE balance.bank_code = delta.bank_code
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StatusTransitionPolicy transitionPolicy;

    IncomingStatusReportPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transitionPolicy = new StatusTransitionPolicy();
    }

    StatusReportPersistenceResult classifyAndApply(List<AuthenticatedStatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return new StatusReportPersistenceResult(List.of(), List.of(), List.of(), List.of());
        }

        PreparedBatch preparedBatch = transitionPolicy.prepare(statusReports);
        Map<Integer, AuthenticatedStatusReport> reportsByOrdinal = preparedBatch.reportsByOrdinal();
        List<PaymentSettlement> settlements = new ArrayList<>();
        List<PaymentRejection> rejectedPayments = new ArrayList<>();
        Set<Integer> divergentStatusReportOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedStatusReportOrdinals = new LinkedHashSet<>();

        List<StatusReportActionRow> actionRows = classifyAndApplyStatusReports(
                preparedBatch.candidatesToClassify()
        );
        for (StatusReportActionRow actionRow : actionRows) {
            AuthenticatedStatusReport statusReport = reportsByOrdinal.get(actionRow.ordinal());
            if (statusReport == null) {
                throw new IllegalStateException("Unknown status report ordinal: " + actionRow.ordinal());
            }

            switch (actionRow.action()) {
                case SETTLED_PAYMENT -> settlements.add(new PaymentSettlement(
                        toPaymentReference(actionRow),
                        actionRow.externalReasonCodes()
                ));
                case REJECTED_NOTIFICATION -> rejectedPayments.add(PaymentRejection.receiverRejected(
                        toPaymentReference(actionRow),
                        actionRow.externalReasonCodes()
                ));
                case STATUS_REPORT_CONFLICT -> addExpandedOrdinals(
                        divergentStatusReportOrdinals,
                        preparedBatch.originalOrdinalsByRepresentative(),
                        actionRow.ordinal()
                );
                case UNAUTHORIZED_PSP -> addExpandedOrdinals(
                        unauthorizedStatusReportOrdinals,
                        preparedBatch.originalOrdinalsByRepresentative(),
                        actionRow.ordinal()
                );
            }
        }

        return new StatusReportPersistenceResult(
                settlements,
                rejectedPayments,
                reportsWithOrdinals(statusReports, divergentStatusReportOrdinals),
                reportsWithOrdinals(statusReports, unauthorizedStatusReportOrdinals)
        );
    }

    private List<StatusReportActionRow> classifyAndApplyStatusReports(List<Candidate> statusReports) {
        return jdbcTemplate.execute((ConnectionCallback<List<StatusReportActionRow>>) connection -> {
            Map<Integer, Candidate> reportsByOrdinal = new LinkedHashMap<>();
            for (Candidate statusReport : statusReports) {
                reportsByOrdinal.put(statusReport.ordinal(), statusReport);
            }

            List<LockedPayment> lockedRows = lockExistingPayments(
                    connection,
                    statusReports,
                    reportsByOrdinal
            );
            List<StatusReportActionRow> actions = new ArrayList<>();
            Decision decision = transitionPolicy.decide(statusReports, lockedRows);
            for (Classification classification : decision.classifications()) {
                Candidate statusReport = reportsByOrdinal.get(classification.ordinal());
                if (statusReport == null) {
                    throw new IllegalStateException("Unknown status report ordinal: " + classification.ordinal());
                }
                actions.add(classificationAction(
                        statusReport,
                        switch (classification.type()) {
                            case STATUS_REPORT_CONFLICT -> Action.STATUS_REPORT_CONFLICT;
                            case UNAUTHORIZED_PSP -> Action.UNAUTHORIZED_PSP;
                        }
                ));
            }

            lockRequiredBalances(connection, decision.transitions());
            List<AcquiredTransition> acquiredTransitions = acquireTransitions(
                    connection,
                    decision.transitions()
            );
            applyBalanceDeltas(connection, acquiredTransitions);
            for (AcquiredTransition transition : acquiredTransitions) {
                actions.add(new StatusReportActionRow(
                        transition.ordinal(),
                        transition.resultingState() == PaymentState.SETTLED
                                ? Action.SETTLED_PAYMENT
                                : Action.REJECTED_NOTIFICATION,
                        transition.paymentId(),
                        transition.amountCents(),
                        transition.senderBankCode(),
                        transition.receiverBankCode(),
                        transition.externalReasonCodes()
                ));
            }

            actions.sort((first, second) -> Integer.compare(first.ordinal(), second.ordinal()));
            return actions;
        });
    }

    private List<LockedPayment> lockExistingPayments(
            Connection connection,
            List<Candidate> statusReports,
            Map<Integer, Candidate> reportsByOrdinal
    ) throws SQLException {
        IncomingStatusReportArrays incoming = incomingStatusReportArrays(statusReports);
        Array ordinalArray = null;
        Array paymentIdArray = null;
        Array requestedOutcomeArray = null;
        Array authenticatedIspbArray = null;
        try {
            ordinalArray = connection.createArrayOf("int4", incoming.ordinals());
            paymentIdArray = connection.createArrayOf("text", incoming.paymentIds());
            requestedOutcomeArray = connection.createArrayOf("text", incoming.requestedOutcomes());
            authenticatedIspbArray = connection.createArrayOf("text", incoming.authenticatedIspbs());
            try (var statement = connection.prepareStatement(LOCK_PAYMENTS_SQL)) {
                statement.setArray(1, ordinalArray);
                statement.setArray(2, paymentIdArray);
                statement.setArray(3, requestedOutcomeArray);
                statement.setArray(4, authenticatedIspbArray);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<LockedPayment> rows = new ArrayList<>(statusReports.size());
                    while (resultSet.next()) {
                        int ordinal = resultSet.getInt(1);
                        Candidate incomingReport = reportsByOrdinal.get(ordinal);
                        if (incomingReport == null) {
                            throw new IllegalStateException("Unknown status report ordinal: " + ordinal);
                        }
                        rows.add(new LockedPayment(
                                ordinal,
                                resultSet.getString(2),
                                StatusReportOutcome.valueOf(resultSet.getString(3)),
                                incomingReport.statusReport().command().reasonCodes(),
                                resultSet.getString(4),
                                PaymentState.valueOf(resultSet.getString(5)),
                                rejectionCause(resultSet.getString(6)),
                                reasonCodes(resultSet.getArray(7)),
                                resultSet.getLong(8),
                                resultSet.getString(9),
                                resultSet.getString(10)
                        ));
                    }
                    return rows;
                }
            }
        } finally {
            free(ordinalArray, paymentIdArray, requestedOutcomeArray, authenticatedIspbArray);
        }
    }

    private PaymentRejectionCause rejectionCause(String rejectionCause) {
        return rejectionCause == null ? null : PaymentRejectionCause.valueOf(rejectionCause);
    }

    private List<StatusReasonCode> reasonCodes(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) sqlArray.getArray();
            List<StatusReasonCode> codes = new ArrayList<>(values.length);
            for (Object value : values) {
                codes.add(StatusReasonCode.of(String.valueOf(value)));
            }
            return StatusReasonCode.normalize(codes);
        } finally {
            sqlArray.free();
        }
    }

    private void lockRequiredBalances(
            Connection connection,
            List<Transition> transitionCandidates
    ) throws SQLException {
        Set<String> requiredIspbs = new TreeSet<>();
        for (Transition candidate : transitionCandidates) {
            requiredIspbs.add(candidate.resultingState() == PaymentState.SETTLED
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
            List<Transition> transitionCandidates
    ) throws SQLException {
        if (transitionCandidates.isEmpty()) {
            return List.of();
        }

        Map<TransitionKey, List<Transition>> candidatesByTransition = new LinkedHashMap<>();
        for (Transition candidate : transitionCandidates) {
            TransitionKey key = new TransitionKey(
                    candidate.resultingState(),
                    candidate.externalReasonCodes()
            );
            candidatesByTransition.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }

        List<AcquiredTransition> acquired = new ArrayList<>(transitionCandidates.size());
        for (Map.Entry<TransitionKey, List<Transition>> entry : candidatesByTransition.entrySet()) {
            List<Transition> candidates = entry.getValue();
            acquireTransitionsForKey(connection, entry.getKey(), candidates);
            for (Transition candidate : candidates) {
                LockedPayment payment = candidate.payment();
                acquired.add(new AcquiredTransition(
                        payment.ordinal(),
                        payment.paymentId(),
                        candidate.resultingState(),
                        payment.amountCents(),
                        payment.senderBankCode(),
                        payment.receiverBankCode(),
                        candidate.externalReasonCodes()
                ));
            }
        }
        acquired.sort((first, second) -> Integer.compare(first.ordinal(), second.ordinal()));
        return acquired;
    }

    private void acquireTransitionsForKey(
            Connection connection,
            TransitionKey transition,
            List<Transition> transitionCandidates
    ) throws SQLException {
        String[] paymentIds = new String[transitionCandidates.size()];
        for (int index = 0; index < transitionCandidates.size(); index++) {
            paymentIds[index] = transitionCandidates.get(index).payment().paymentId();
        }

        Array reasonCodeArray = null;
        Array paymentIdArray = null;
        try {
            reasonCodeArray = connection.createArrayOf(
                    "text",
                    transition.externalReasonCodes().stream()
                            .map(StatusReasonCode::value)
                            .toArray(String[]::new)
            );
            paymentIdArray = connection.createArrayOf("text", paymentIds);
            try (var statement = connection.prepareStatement(ACQUIRE_TRANSITIONS_SQL)) {
                statement.setString(1, transition.resultingState().name());
                statement.setArray(2, reasonCodeArray);
                statement.setArray(3, paymentIdArray);
                statement.setString(4, PaymentState.WAITING_ACCEPTANCE.name());
                int acquired = statement.executeUpdate();
                if (acquired != transitionCandidates.size()) {
                    throw new IllegalStateException("Could not acquire every waiting payment transition");
                }
            }
        } finally {
            free(reasonCodeArray, paymentIdArray);
        }
    }

    private void applyBalanceDeltas(
            Connection connection,
            List<AcquiredTransition> acquiredTransitions
    ) throws SQLException {
        Map<String, Long> deltasByParticipant = new TreeMap<>();
        for (AcquiredTransition transition : acquiredTransitions) {
            String participantIspb = transition.resultingState() == PaymentState.SETTLED
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

    private StatusReportActionRow classificationAction(Candidate statusReport, Action action) {
        return new StatusReportActionRow(
                statusReport.ordinal(),
                action,
                statusReport.paymentId(),
                null,
                null,
                null,
                List.of()
        );
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

    private IncomingStatusReportArrays incomingStatusReportArrays(List<Candidate> statusReports) {
        int size = statusReports.size();
        Integer[] ordinals = new Integer[size];
        String[] paymentIds = new String[size];
        String[] requestedOutcomes = new String[size];
        String[] authenticatedIspbs = new String[size];

        for (int index = 0; index < statusReports.size(); index++) {
            Candidate statusReportRow = statusReports.get(index);
            AuthenticatedStatusReport authenticatedStatusReport = statusReportRow.statusReport();
            IncomingStatusReportCommand statusReport = authenticatedStatusReport.command();
            ordinals[index] = statusReportRow.ordinal();
            paymentIds[index] = statusReport.originalPaymentId();
            requestedOutcomes[index] = statusReport.outcome().name();
            authenticatedIspbs[index] = authenticatedStatusReport.authenticatedIspb();
        }

        return new IncomingStatusReportArrays(
                ordinals,
                paymentIds,
                requestedOutcomes,
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

    private PaymentReference toPaymentReference(StatusReportActionRow actionRow) {
        return new PaymentReference(
                actionRow.paymentId(),
                actionRow.amountCents(),
                actionRow.senderBankCode(),
                actionRow.receiverBankCode()
        );
    }

    private enum Action {
        SETTLED_PAYMENT,
        REJECTED_NOTIFICATION,
        STATUS_REPORT_CONFLICT,
        UNAUTHORIZED_PSP
    }

    private record TransitionKey(
            PaymentState resultingState,
            List<StatusReasonCode> externalReasonCodes
    ) {
    }

    private record AcquiredTransition(
            int ordinal,
            String paymentId,
            PaymentState resultingState,
            long amountCents,
            String senderBankCode,
            String receiverBankCode,
            List<StatusReasonCode> externalReasonCodes
    ) {
    }

    private record StatusReportActionRow(
            int ordinal,
            Action action,
            String paymentId,
            Long amountCents,
            String senderBankCode,
            String receiverBankCode,
            List<StatusReasonCode> externalReasonCodes
    ) {
    }

    private record IncomingStatusReportArrays(
            Integer[] ordinals,
            String[] paymentIds,
            String[] requestedOutcomes,
            String[] authenticatedIspbs
    ) {
        private IncomingStatusReportArrays {
            int size = ordinals.length;
            if (paymentIds.length != size
                    || requestedOutcomes.length != size
                    || authenticatedIspbs.length != size) {
                throw new IllegalStateException("Incoming status report arrays must have the same size");
            }
        }
    }
}
