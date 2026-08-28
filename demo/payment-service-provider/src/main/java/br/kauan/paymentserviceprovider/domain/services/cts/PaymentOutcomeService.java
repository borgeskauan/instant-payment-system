package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class PaymentOutcomeService {

    private final PaymentStore paymentStore;
    private final PspStateStore stateStore;

    public PaymentOutcomeService(PaymentStore paymentStore, PspStateStore stateStore) {
        this.paymentStore = paymentStore;
        this.stateStore = stateStore;
    }

    public void handleStatuses(List<StatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return;
        }

        List<StatusReport> balanceOutcomes = statusReports.stream()
                .filter(report -> report.getStatus() != PaymentStatus.REJECTED)
                .toList();
        int rejectedCount = statusReports.size() - balanceOutcomes.size();

        if (rejectedCount > 0) {
            log.info("PSP received {} rejected payment outcomes; local balance is unchanged", rejectedCount);
        }
        if (balanceOutcomes.isEmpty()) {
            return;
        }

        Map<String, PaymentTransaction> paymentsById = findPayments(balanceOutcomes);
        Map<BankAccountId, BigDecimal> balanceDeltas = new HashMap<>();
        List<ClaimedOutcome> claimedOutcomes = new ArrayList<>();
        int inProcessCount = 0;

        for (StatusReport outcome : balanceOutcomes) {
            PaymentTransaction payment = paymentsById.get(outcome.getOriginalPaymentId());
            switch (outcome.getStatus()) {
                case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> claimAndAddDelta(
                        outcome,
                        payment.getReceiver().getAccount().getId(),
                        payment.getAmount(),
                        balanceDeltas,
                        claimedOutcomes
                );
                case ACCEPTED_AND_SETTLED_FOR_SENDER -> claimAndAddDelta(
                        outcome,
                        payment.getSender().getAccount().getId(),
                        payment.getAmount().negate(),
                        balanceDeltas,
                        claimedOutcomes
                );
                case ACCEPTED_IN_PROCESS -> inProcessCount++;
                default -> throw new IllegalArgumentException("Unsupported payment outcome: " + outcome.getStatus());
            }
        }

        try {
            if (!balanceDeltas.isEmpty()) {
                stateStore.applyBalanceDeltas(balanceDeltas);
            }
            claimedOutcomes.forEach(this::markApplied);
        } catch (RuntimeException e) {
            claimedOutcomes.forEach(this::releaseClaim);
            throw e;
        }

        log.info("PSP handled {} final balance changes and {} in-process outcomes",
                claimedOutcomes.size(), inProcessCount);
    }

    private Map<String, PaymentTransaction> findPayments(List<StatusReport> outcomes) {
        List<String> paymentIds = outcomes.stream()
                .map(StatusReport::getOriginalPaymentId)
                .toList();
        Map<String, PaymentTransaction> paymentsById = new HashMap<>();
        for (PaymentTransaction payment : paymentStore.findAllByIds(paymentIds)) {
            paymentsById.put(payment.getPaymentId(), payment);
        }
        for (StatusReport outcome : outcomes) {
            if (!paymentsById.containsKey(outcome.getOriginalPaymentId())) {
                throw new IllegalArgumentException("Payment not found: " + outcome.getOriginalPaymentId());
            }
        }
        return paymentsById;
    }

    private void claimAndAddDelta(
            StatusReport outcome,
            BankAccountId accountId,
            BigDecimal delta,
            Map<BankAccountId, BigDecimal> balanceDeltas,
            List<ClaimedOutcome> claimedOutcomes
    ) {
        if (!paymentStore.claimFinalStatus(outcome.getOriginalPaymentId(), outcome.getStatus())) {
            return;
        }

        balanceDeltas.merge(accountId, delta, BigDecimal::add);
        claimedOutcomes.add(new ClaimedOutcome(outcome.getOriginalPaymentId(), outcome.getStatus()));
    }

    private void markApplied(ClaimedOutcome outcome) {
        paymentStore.markFinalStatusApplied(outcome.paymentId(), outcome.status());
    }

    private void releaseClaim(ClaimedOutcome outcome) {
        paymentStore.releaseFinalStatusClaim(outcome.paymentId(), outcome.status());
    }

    private record ClaimedOutcome(String paymentId, PaymentStatus status) {
    }
}
