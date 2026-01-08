package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
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
        log.info("[PIX FLOW - Step 8/9] PSP handling settlement for payment {} with status: {}", 
                payment.getPaymentId(), status);

        switch (status) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> {
                log.info("[PIX FLOW - Step 8] PSP Recebedor crediting receiver account");
                creditReceiverAccount(payment);
                log.info("=== [PIX FLOW COMPLETE - Receiver] Cliente Recebedor credited successfully ===");
            }
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> {
                log.info("[PIX FLOW - Step 9] PSP Pagador debiting sender account");
                debitSenderAccount(payment);
                log.info("=== [PIX FLOW COMPLETE - Sender] Cliente Pagador debited successfully ===");
            }
            case ACCEPTED_IN_PROCESS -> log.debug("[PIX FLOW] Payment {} accepted and in process", payment.getPaymentId());
            default -> log.warn("[PIX FLOW] Unhandled payment status: {} for payment: {}", status, payment.getPaymentId());
        }
    }

    private void creditReceiverAccount(PaymentTransaction payment) {
        BankAccountId bankAccountId = getBankAccountId(payment.getReceiver().getAccount());
        bankAccountPartyService.addAmountToAccount(bankAccountId, payment.getAmount());
        log.info("[PIX FLOW - Step 8] Cliente Recebedor credited: Amount {} to account {}", 
                payment.getAmount(), bankAccountId);
    }

    private void debitSenderAccount(PaymentTransaction payment) {
        BankAccountId bankAccountId = getBankAccountId(payment.getSender().getAccount());
        bankAccountPartyService.removeAmountFromAccount(bankAccountId, payment.getAmount());
        log.info("[PIX FLOW - Step 9] Cliente Pagador debited: Amount {} from account {}", 
                payment.getAmount(), bankAccountId);
    }

    private BankAccountId getBankAccountId(BankAccount account) {
        return account.getId();
    }
}