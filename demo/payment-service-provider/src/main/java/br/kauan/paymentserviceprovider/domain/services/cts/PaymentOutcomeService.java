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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class PaymentOutcomeService {

    private final PaymentStore paymentStore;
    private final PspStateStore stateStore;

    public PaymentOutcomeService(PaymentStore paymentStore, PspStateStore stateStore) {
        this.paymentStore = paymentStore;
        this.stateStore = stateStore;
    }

    public synchronized void handleStatuses(List<StatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return;
        }

        int rejectedCount = (int) statusReports.stream()
                .filter(report -> report.getStatus() == PaymentStatus.REJECTED)
                .count();

        if (rejectedCount > 0) {
            log.info("PSP received {} rejected payment outcomes; local balance is unchanged", rejectedCount);
        }
        Map<String, PaymentTransaction> paymentsById = findPayments(statusReports);
        List<FinalOutcome> newOutcomes = selectNewFinalOutcomes(statusReports);
        Map<BankAccountId, BigDecimal> balanceDeltas = new HashMap<>();

        for (FinalOutcome outcome : newOutcomes) {
            PaymentTransaction payment = paymentsById.get(outcome.paymentId());
            switch (outcome.status()) {
                case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> balanceDeltas.merge(
                        payment.getReceiver().getAccount().getId(), payment.getAmount(), BigDecimal::add);
                case ACCEPTED_AND_SETTLED_FOR_SENDER -> balanceDeltas.merge(
                        payment.getSender().getAccount().getId(), payment.getAmount().negate(), BigDecimal::add);
                case REJECTED -> {
                }
                case ACCEPTED_IN_PROCESS -> throw new IllegalStateException("In-process outcome cannot be final");
            }
        }

        if (!balanceDeltas.isEmpty()) {
            stateStore.applyBalanceDeltas(balanceDeltas);
        }
        newOutcomes.forEach(this::markApplied);

        long inProcessCount = statusReports.stream()
                .filter(report -> report.getStatus() == PaymentStatus.ACCEPTED_IN_PROCESS)
                .count();
        log.info("PSP handled {} final outcomes and {} in-process outcomes",
                newOutcomes.size(), inProcessCount);
    }

    private List<FinalOutcome> selectNewFinalOutcomes(List<StatusReport> outcomes) {
        Map<String, Set<PaymentStatus>> statusesByPayment = new HashMap<>();
        List<FinalOutcome> newOutcomes = new ArrayList<>();

        for (StatusReport outcome : outcomes) {
            if (outcome.getStatus() == PaymentStatus.ACCEPTED_IN_PROCESS) {
                continue;
            }

            String paymentId = outcome.getOriginalPaymentId();
            Set<PaymentStatus> statuses = statusesByPayment.computeIfAbsent(
                    paymentId,
                    id -> new HashSet<>(paymentStore.findAppliedFinalStatuses(id))
            );
            if (conflictsWith(outcome.getStatus(), statuses)) {
                throw new IllegalArgumentException("Contradictory final outcome for payment: " + paymentId);
            }
            if (statuses.add(outcome.getStatus())) {
                newOutcomes.add(new FinalOutcome(paymentId, outcome.getStatus()));
            }
        }
        return newOutcomes;
    }

    private boolean conflictsWith(PaymentStatus status, Set<PaymentStatus> existingStatuses) {
        if (status == PaymentStatus.REJECTED) {
            return existingStatuses.contains(PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
                    || existingStatuses.contains(PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
        }
        return existingStatuses.contains(PaymentStatus.REJECTED);
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

    private void markApplied(FinalOutcome outcome) {
        paymentStore.markFinalStatusApplied(outcome.paymentId(), outcome.status());
    }

    private record FinalOutcome(String paymentId, PaymentStatus status) {
    }
}
