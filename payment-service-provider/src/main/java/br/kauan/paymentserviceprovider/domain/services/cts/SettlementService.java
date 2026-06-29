package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.services.BankAccountPartyService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@Transactional
public class SettlementService {
    
    private final BankAccountPartyService bankAccountPartyService;

    public SettlementService(BankAccountPartyService bankAccountPartyService) {
        this.bankAccountPartyService = bankAccountPartyService;
    }

    public void handleSettlements(List<StatusReport> statusReports, Map<String, PaymentTransaction> paymentsById) {
        if (statusReports.isEmpty()) {
            return;
        }

        Map<BankAccountId, BigDecimal> creditsByAccount = new HashMap<>();
        Map<BankAccountId, BigDecimal> debitsByAccount = new HashMap<>();
        int inProcessCount = 0;

        for (StatusReport statusReport : statusReports) {
            PaymentTransaction payment = paymentsById.get(statusReport.getOriginalPaymentId());
            if (payment == null) {
                throw new IllegalArgumentException("Payment not found: " + statusReport.getOriginalPaymentId());
            }

            switch (statusReport.getStatus()) {
                case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> addAmount(creditsByAccount, payment.getReceiver().getAccount(), payment.getAmount());
                case ACCEPTED_AND_SETTLED_FOR_SENDER -> addAmount(debitsByAccount, payment.getSender().getAccount(), payment.getAmount());
                case ACCEPTED_IN_PROCESS -> inProcessCount++;
                default -> log.warn("[PIX FLOW] Unhandled payment status: {} for payment: {}",
                        statusReport.getStatus(), payment.getPaymentId());
            }
        }

        bankAccountPartyService.addAmountsToAccounts(creditsByAccount);
        bankAccountPartyService.removeAmountsFromAccounts(debitsByAccount);

        log.info("[PIX FLOW - Step 8/9] PSP handled settlement batch. Credits: {}, Debits: {}, In process: {}",
                creditsByAccount.size(), debitsByAccount.size(), inProcessCount);
    }

    private void addAmount(Map<BankAccountId, BigDecimal> amountsByAccount, BankAccount account, BigDecimal amount) {
        amountsByAccount.merge(getBankAccountId(account), amount, BigDecimal::add);
    }

    private BankAccountId getBankAccountId(BankAccount account) {
        return account.getId();
    }
}
