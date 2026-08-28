package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.services.BankAccountPartyService;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@Transactional
public class SettlementService {
    
    private final BankAccountPartyService bankAccountPartyService;
    private final PaymentRepository paymentRepository;

    public SettlementService(BankAccountPartyService bankAccountPartyService, PaymentRepository paymentRepository) {
        this.bankAccountPartyService = bankAccountPartyService;
        this.paymentRepository = paymentRepository;
    }

    public void handleSettlements(List<StatusReport> statusReports, Map<String, PaymentTransaction> paymentsById) {
        if (statusReports.isEmpty()) {
            return;
        }

        Map<BankAccountId, BigDecimal> creditsByAccount = new HashMap<>();
        Map<BankAccountId, BigDecimal> debitsByAccount = new HashMap<>();
        List<ClaimedFinalStatus> claimedFinalStatuses = new ArrayList<>();
        int inProcessCount = 0;

        for (StatusReport statusReport : statusReports) {
            PaymentTransaction payment = paymentsById.get(statusReport.getOriginalPaymentId());
            if (payment == null) {
                throw new IllegalArgumentException("Payment not found: " + statusReport.getOriginalPaymentId());
            }

            switch (statusReport.getStatus()) {
                case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> {
                    if (claimFinalStatus(statusReport)) {
                        addAmount(creditsByAccount, payment.getReceiver().getAccount(), payment.getAmount());
                        claimedFinalStatuses.add(claimedFinalStatus(statusReport));
                    }
                }
                case ACCEPTED_AND_SETTLED_FOR_SENDER -> {
                    if (claimFinalStatus(statusReport)) {
                        addAmount(debitsByAccount, payment.getSender().getAccount(), payment.getAmount());
                        claimedFinalStatuses.add(claimedFinalStatus(statusReport));
                    }
                }
                case ACCEPTED_IN_PROCESS -> inProcessCount++;
                default -> log.warn("[PIX FLOW] Unhandled payment status: {} for payment: {}",
                        statusReport.getStatus(), payment.getPaymentId());
            }
        }

        try {
            if (!creditsByAccount.isEmpty()) {
                bankAccountPartyService.addAmountsToAccounts(creditsByAccount);
            }
            if (!debitsByAccount.isEmpty()) {
                bankAccountPartyService.removeAmountsFromAccounts(debitsByAccount);
            }
            markFinalStatusesApplied(claimedFinalStatuses);
        } catch (RuntimeException e) {
            releaseFinalStatusClaims(claimedFinalStatuses);
            throw e;
        }

        log.info("[PIX FLOW - Step 8/9] PSP handled settlement batch. Credits: {}, Debits: {}, In process: {}",
                creditsByAccount.size(), debitsByAccount.size(), inProcessCount);
    }

    private boolean claimFinalStatus(StatusReport statusReport) {
        return paymentRepository.claimFinalStatusApplication(
                statusReport.getOriginalPaymentId(),
                statusReport.getStatus()
        );
    }

    private ClaimedFinalStatus claimedFinalStatus(StatusReport statusReport) {
        return new ClaimedFinalStatus(statusReport.getOriginalPaymentId(), statusReport.getStatus());
    }

    private void markFinalStatusesApplied(List<ClaimedFinalStatus> claimedFinalStatuses) {
        for (ClaimedFinalStatus claimedFinalStatus : claimedFinalStatuses) {
            paymentRepository.markFinalStatusApplied(claimedFinalStatus.paymentId(), claimedFinalStatus.status());
        }
    }

    private void releaseFinalStatusClaims(List<ClaimedFinalStatus> claimedFinalStatuses) {
        for (ClaimedFinalStatus claimedFinalStatus : claimedFinalStatuses) {
            paymentRepository.releaseFinalStatusApplicationClaim(
                    claimedFinalStatus.paymentId(),
                    claimedFinalStatus.status()
            );
        }
    }

    private void addAmount(Map<BankAccountId, BigDecimal> amountsByAccount, BankAccount account, BigDecimal amount) {
        amountsByAccount.merge(getBankAccountId(account), amount, BigDecimal::add);
    }

    private BankAccountId getBankAccountId(BankAccount account) {
        return account.getId();
    }

    private record ClaimedFinalStatus(String paymentId, PaymentStatus status) {
    }
}
