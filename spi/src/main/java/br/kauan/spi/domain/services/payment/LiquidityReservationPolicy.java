package br.kauan.spi.domain.services.payment;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class LiquidityReservationPolicy {

    public ReservationBatch prepare(List<AuthenticatedPaymentRequest> paymentRequests) {
        Map<String, List<AuthenticatedPaymentRequest>> requestsByPayer = new TreeMap<>();
        for (AuthenticatedPaymentRequest paymentRequest : paymentRequests) {
            requestsByPayer.computeIfAbsent(payerIspb(paymentRequest), ignored -> new ArrayList<>())
                    .add(paymentRequest);
        }
        for (List<AuthenticatedPaymentRequest> payerRequests : requestsByPayer.values()) {
            payerRequests.sort((first, second) -> Integer.compare(first.sourceOrdinal(), second.sourceOrdinal()));
        }
        return new ReservationBatch(requestsByPayer, paymentRequests.size());
    }

    public ReservationPlan plan(
            ReservationBatch batch,
            Map<String, Long> availableBalances
    ) {
        if (!availableBalances.keySet().containsAll(batch.requestsByPayer().keySet())) {
            throw new IllegalStateException("Required participant balance is missing");
        }

        Map<String, Long> debitsByPayer = new TreeMap<>();
        List<String> insufficientPaymentIds = new ArrayList<>();
        List<ReservationOutcome> outcomes = new ArrayList<>(batch.paymentCount());

        for (Map.Entry<String, List<AuthenticatedPaymentRequest>> payerEntry : batch.requestsByPayer().entrySet()) {
            String payerIspb = payerEntry.getKey();
            long remainingBalance = availableBalances.get(payerIspb);
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

        outcomes.sort((first, second) -> Integer.compare(
                first.paymentRequest().sourceOrdinal(),
                second.paymentRequest().sourceOrdinal()
        ));
        return new ReservationPlan(outcomes, debitsByPayer, insufficientPaymentIds);
    }

    private String payerIspb(AuthenticatedPaymentRequest paymentRequest) {
        return Utils.getBankCode(paymentRequest.command().getSender());
    }

    public record ReservationOutcome(AuthenticatedPaymentRequest paymentRequest, boolean reserved) {
    }

    public record ReservationBatch(
            Map<String, List<AuthenticatedPaymentRequest>> requestsByPayer,
            int paymentCount
    ) {
    }

    public record ReservationPlan(
            List<ReservationOutcome> outcomes,
            Map<String, Long> debitsByPayer,
            List<String> insufficientPaymentIds
    ) {
    }
}
