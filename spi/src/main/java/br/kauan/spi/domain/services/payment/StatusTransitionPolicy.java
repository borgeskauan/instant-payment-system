package br.kauan.spi.domain.services.payment;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StatusTransitionPolicy {

    public PreparedBatch prepare(List<AuthenticatedStatusReport> statusReports) {
        Map<String, List<Candidate>> candidatesByPaymentId = new LinkedHashMap<>(mapCapacity(statusReports.size()));
        Map<Integer, AuthenticatedStatusReport> reportsByOrdinal = new LinkedHashMap<>(mapCapacity(statusReports.size()));
        for (AuthenticatedStatusReport statusReport : statusReports) {
            if (reportsByOrdinal.put(statusReport.sourceOrdinal(), statusReport) != null) {
                throw new IllegalArgumentException(
                        "Status report source ordinals must be unique: " + statusReport.sourceOrdinal());
            }
            Candidate candidate = new Candidate(statusReport);
            candidatesByPaymentId.computeIfAbsent(candidate.paymentId(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<Candidate> candidatesToClassify = new ArrayList<>(candidatesByPaymentId.size());
        Map<Integer, List<Integer>> originalOrdinalsByRepresentative =
                new LinkedHashMap<>(mapCapacity(statusReports.size()));

        for (List<Candidate> candidates : candidatesByPaymentId.values()) {
            Candidate first = candidates.getFirst();
            List<Integer> originalOrdinals = new ArrayList<>(candidates.size());
            boolean homogeneous = true;
            for (Candidate candidate : candidates) {
                originalOrdinals.add(candidate.ordinal());
                if (!sameSecurityAndCommand(first, candidate)) {
                    homogeneous = false;
                }
            }

            if (homogeneous) {
                candidatesToClassify.add(first);
                originalOrdinalsByRepresentative.put(first.ordinal(), originalOrdinals);
            } else {
                for (Candidate candidate : candidates) {
                    candidatesToClassify.add(candidate);
                    originalOrdinalsByRepresentative.put(candidate.ordinal(), List.of(candidate.ordinal()));
                }
            }
        }

        return new PreparedBatch(candidatesToClassify, originalOrdinalsByRepresentative, reportsByOrdinal);
    }

    public Decision decide(List<Candidate> candidates, List<LockedPayment> lockedPayments) {
        Map<Integer, LockedPayment> lockedByOrdinal = new LinkedHashMap<>(mapCapacity(lockedPayments.size()));
        for (LockedPayment lockedPayment : lockedPayments) {
            lockedByOrdinal.put(lockedPayment.ordinal(), lockedPayment);
        }

        List<Classification> classifications = new ArrayList<>();
        Map<String, List<LockedPayment>> authorizedByPaymentId = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            LockedPayment lockedPayment = lockedByOrdinal.get(candidate.ordinal());
            if (lockedPayment == null) {
                classifications.add(new Classification(candidate.ordinal(), ClassificationType.STATUS_REPORT_CONFLICT));
            } else if (!Objects.equals(lockedPayment.authenticatedIspb(), lockedPayment.receiverBankCode())) {
                classifications.add(new Classification(candidate.ordinal(), ClassificationType.UNAUTHORIZED_PSP));
            } else {
                authorizedByPaymentId.computeIfAbsent(lockedPayment.paymentId(), ignored -> new ArrayList<>())
                        .add(lockedPayment);
            }
        }

        List<Transition> transitions = new ArrayList<>();
        for (List<LockedPayment> paymentReports : authorizedByPaymentId.values()) {
            if (hasConflictingIdentity(paymentReports)) {
                paymentReports.forEach(payment -> classifications.add(
                        new Classification(payment.ordinal(), ClassificationType.STATUS_REPORT_CONFLICT)));
                continue;
            }

            LockedPayment payment = paymentReports.getFirst();
            if (payment.existingState() == PaymentState.WAITING_ACCEPTANCE) {
                PaymentState resultingState = payment.requestedOutcome() == StatusReportOutcome.ACCEPTED
                        ? PaymentState.SETTLED
                        : PaymentState.REJECTED;
                transitions.add(new Transition(payment, resultingState, payment.requestedReasonCodes()));
            } else if (!terminalReplayIsNoOp(payment)) {
                classifications.add(new Classification(payment.ordinal(), ClassificationType.STATUS_REPORT_CONFLICT));
            }
        }

        return new Decision(classifications, transitions);
    }

    private boolean hasConflictingIdentity(List<LockedPayment> paymentReports) {
        LockedPayment first = paymentReports.getFirst();
        for (LockedPayment payment : paymentReports) {
            if (payment.requestedOutcome() != first.requestedOutcome()
                    || !payment.requestedReasonCodes().equals(first.requestedReasonCodes())) {
                return true;
            }
        }
        return false;
    }

    private boolean terminalReplayIsNoOp(LockedPayment payment) {
        if (payment.existingRejectionCause() != null) {
            return false;
        }

        boolean sameOutcome = switch (payment.requestedOutcome()) {
            case ACCEPTED -> payment.existingState() == PaymentState.SETTLED;
            case REJECTED -> payment.existingState() == PaymentState.REJECTED;
        };
        return sameOutcome && payment.existingExternalReasonCodes().equals(payment.requestedReasonCodes());
    }

    private boolean sameSecurityAndCommand(Candidate first, Candidate candidate) {
        return Objects.equals(first.authenticatedIspb(), candidate.authenticatedIspb())
                && first.statusReport().command().equals(candidate.statusReport().command());
    }

    private int mapCapacity(int expectedSize) {
        return Math.max(16, expectedSize * 4 / 3 + 1);
    }

    public enum ClassificationType {
        STATUS_REPORT_CONFLICT,
        UNAUTHORIZED_PSP
    }

    public record Candidate(AuthenticatedStatusReport statusReport) {
        public int ordinal() {
            return statusReport.sourceOrdinal();
        }

        public String paymentId() {
            return statusReport.command().originalPaymentId();
        }

        public String authenticatedIspb() {
            return statusReport.authenticatedIspb();
        }
    }

    public record LockedPayment(
            int ordinal,
            String paymentId,
            StatusReportOutcome requestedOutcome,
            List<StatusReasonCode> requestedReasonCodes,
            String authenticatedIspb,
            PaymentState existingState,
            PaymentRejectionCause existingRejectionCause,
            List<StatusReasonCode> existingExternalReasonCodes,
            long amountCents,
            String senderBankCode,
            String receiverBankCode
    ) {
    }

    public record Transition(
            LockedPayment payment,
            PaymentState resultingState,
            List<StatusReasonCode> externalReasonCodes
    ) {
    }

    public record Classification(int ordinal, ClassificationType type) {
    }

    public record Decision(List<Classification> classifications, List<Transition> transitions) {
    }

    public record PreparedBatch(
            List<Candidate> candidatesToClassify,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative,
            Map<Integer, AuthenticatedStatusReport> reportsByOrdinal
    ) {
    }
}
