package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.services.BankAccountPartyService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
public class SettlementService {
    
    private final BankAccountPartyService bankAccountPartyService;

    public SettlementService(BankAccountPartyService bankAccountPartyService) {
        this.bankAccountPartyService = bankAccountPartyService;
    }

    public void handleSettlement(PaymentStatus status, PaymentTransaction payment) {
        log.debug("Handling settlement for payment {} with status: {}", payment.getPaymentId(), status);

        switch (status) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> creditReceiverAccount(payment);
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> debitSenderAccount(payment);
            case ACCEPTED_IN_PROCESS -> log.debug("Payment {} accepted and in process", payment.getPaymentId());
            default -> log.warn("Unhandled payment status: {} for payment: {}", status, payment.getPaymentId());
        }
    }

    private void creditReceiverAccount(PaymentTransaction payment) {
        BankAccountId bankAccountId = getBankAccountId(payment.getReceiver().getAccount());
        bankAccountPartyService.addAmountToAccount(bankAccountId, payment.getAmount());
        log.info("Credited amount {} to receiver account {}", payment.getAmount(), bankAccountId);
    }

    private void debitSenderAccount(PaymentTransaction payment) {
        BankAccountId bankAccountId = getBankAccountId(payment.getSender().getAccount());
        bankAccountPartyService.removeAmountFromAccount(bankAccountId, payment.getAmount());
        log.info("Debited amount {} from sender account {}", payment.getAmount(), bankAccountId);
    }

    private BankAccountId getBankAccountId(BankAccount account) {
        return account.getId();
    }
}