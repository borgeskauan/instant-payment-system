package br.kauan.spi.domain.services.payment;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PaymentAdmissionPolicy {

    public PreparedBatch prepare(List<AuthenticatedPaymentRequest> paymentRequests) {
        validateUniqueSourceOrdinals(paymentRequests);

        Map<String, List<Candidate>> rowsByPaymentId = new LinkedHashMap<>(mapCapacity(paymentRequests.size()));
        Set<Integer> unauthorizedOrdinals = new LinkedHashSet<>();
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            Candidate candidate = new Candidate(paymentRequest, RequestFingerprint.calculate(paymentRequest.command()));
            if (!Objects.equals(paymentRequest.authenticatedIspb(), payerIspb(paymentRequest))) {
                unauthorizedOrdinals.add(paymentRequest.sourceOrdinal());
                continue;
            }
            rowsByPaymentId.computeIfAbsent(candidate.paymentId(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<Candidate> insertionCandidates = new ArrayList<>(rowsByPaymentId.size());
        Map<Integer, List<Integer>> originalOrdinalsByRepresentative =
                new LinkedHashMap<>(mapCapacity(paymentRequests.size()));
        List<List<Candidate>> nonHomogeneousGroups = new ArrayList<>();

        for (List<Candidate> candidates : rowsByPaymentId.values()) {
            Candidate first = candidates.getFirst();
            List<Integer> originalOrdinals = new ArrayList<>(candidates.size());
            boolean homogeneous = true;
            for (Candidate candidate : candidates) {
                originalOrdinals.add(candidate.ordinal());
                if (!sameSecurityAndFingerprint(first, candidate)) {
                    homogeneous = false;
                }
            }

            if (homogeneous) {
                insertionCandidates.add(first);
                originalOrdinalsByRepresentative.put(first.ordinal(), originalOrdinals);
            } else {
                nonHomogeneousGroups.add(candidates);
            }
        }

        return new PreparedBatch(
                insertionCandidates,
                originalOrdinalsByRepresentative,
                nonHomogeneousGroups,
                unauthorizedOrdinals
        );
    }

    public Classification classifyNonHomogeneousGroups(
            List<List<Candidate>> groups,
            Map<String, ExistingPayment> existingPayments
    ) {
        Set<Integer> divergentOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedOrdinals = new LinkedHashSet<>();

        for (List<Candidate> candidates : groups) {
            ExistingPayment existingPayment = existingPayments.get(candidates.getFirst().paymentId());
            if (existingPayment == null) {
                candidates.forEach(candidate -> divergentOrdinals.add(candidate.ordinal()));
                continue;
            }

            List<Candidate> ownerCandidates = new ArrayList<>(candidates.size());
            for (Candidate candidate : candidates) {
                if (Objects.equals(candidate.authenticatedIspb(), existingPayment.senderBankCode())) {
                    ownerCandidates.add(candidate);
                } else {
                    unauthorizedOrdinals.add(candidate.ordinal());
                }
            }
            if (ownerCandidates.isEmpty()) {
                continue;
            }

            Candidate representative = ownerCandidates.getFirst();
            boolean ownerCandidatesDiverge = ownerCandidates.stream()
                    .anyMatch(candidate -> !representative.fingerprint().equals(candidate.fingerprint()));
            if (ownerCandidatesDiverge || !representative.fingerprint().matches(
                    existingPayment.requestFingerprint(),
                    existingPayment.requestFingerprintVersion()
            )) {
                ownerCandidates.forEach(candidate -> divergentOrdinals.add(candidate.ordinal()));
            }
        }

        return new Classification(divergentOrdinals, unauthorizedOrdinals);
    }

    public Classification classifyInsertionConflicts(
            List<Candidate> conflicts,
            Map<String, ExistingPayment> existingPayments,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative
    ) {
        Set<Integer> divergentOrdinals = new LinkedHashSet<>();
        Set<Integer> unauthorizedOrdinals = new LinkedHashSet<>();

        for (Candidate conflict : conflicts) {
            ExistingPayment existingPayment = existingPayments.get(conflict.paymentId());
            if (existingPayment == null) {
                throw new IllegalStateException("Payment conflict could not be reclassified: " + conflict.paymentId());
            }

            Set<Integer> destination;
            if (!Objects.equals(conflict.authenticatedIspb(), existingPayment.senderBankCode())) {
                destination = unauthorizedOrdinals;
            } else if (!conflict.fingerprint().matches(
                    existingPayment.requestFingerprint(),
                    existingPayment.requestFingerprintVersion()
            )) {
                destination = divergentOrdinals;
            } else {
                continue;
            }
            destination.addAll(originalOrdinals(originalOrdinalsByRepresentative, conflict.ordinal()));
        }

        return new Classification(divergentOrdinals, unauthorizedOrdinals);
    }

    private void validateUniqueSourceOrdinals(List<AuthenticatedPaymentRequest> paymentRequests) {
        Set<Integer> sourceOrdinals = new LinkedHashSet<>(mapCapacity(paymentRequests.size()));
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            if (!sourceOrdinals.add(paymentRequest.sourceOrdinal())) {
                throw new IllegalArgumentException(
                        "Payment request source ordinals must be unique: " + paymentRequest.sourceOrdinal());
            }
        }
    }

    private List<Integer> originalOrdinals(
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative,
            int representativeOrdinal
    ) {
        List<Integer> originalOrdinals = originalOrdinalsByRepresentative.get(representativeOrdinal);
        if (originalOrdinals == null) {
            throw new IllegalStateException("Unknown payment request representative ordinal: " + representativeOrdinal);
        }
        return originalOrdinals;
    }

    private boolean sameSecurityAndFingerprint(Candidate first, Candidate candidate) {
        return Objects.equals(first.authenticatedIspb(), candidate.authenticatedIspb())
                && first.fingerprint().equals(candidate.fingerprint());
    }

    private String payerIspb(AuthenticatedPaymentRequest paymentRequest) {
        return paymentRequest.command().senderIspb();
    }

    private int mapCapacity(int expectedSize) {
        return Math.max(16, expectedSize * 4 / 3 + 1);
    }

    public record Candidate(AuthenticatedPaymentRequest paymentRequest, RequestFingerprint fingerprint) {
        public int ordinal() {
            return paymentRequest.sourceOrdinal();
        }

        public String paymentId() {
            return paymentRequest.command().getPaymentId();
        }

        public String authenticatedIspb() {
            return paymentRequest.authenticatedIspb();
        }
    }

    public record ExistingPayment(
            String paymentId,
            String senderBankCode,
            byte[] requestFingerprint,
            Short requestFingerprintVersion
    ) {
    }

    public record PreparedBatch(
            List<Candidate> insertionCandidates,
            Map<Integer, List<Integer>> originalOrdinalsByRepresentative,
            List<List<Candidate>> nonHomogeneousGroups,
            Set<Integer> unauthorizedOrdinals
    ) {
    }

    public record Classification(Set<Integer> divergentOrdinals, Set<Integer> unauthorizedOrdinals) {
    }
}
