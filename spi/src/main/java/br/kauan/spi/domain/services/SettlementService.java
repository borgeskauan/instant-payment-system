package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.FundsRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {
    private final FundsRepository fundsRepository;

    public SettlementService(FundsRepository fundsRepository) {
        this.fundsRepository = fundsRepository;
    }

    @Transactional
    public void makeSettlement(PaymentTransaction paymentTransaction) {
        var amount = paymentTransaction.getAmount();
        var senderBankCode = paymentTransaction.getSender().getAccount().getBankCode();
        var receiverBankCode = paymentTransaction.getReceiver().getAccount().getBankCode();

        var senderAvailableFunds = fundsRepository.getAvailableFunds(senderBankCode);
        if (senderAvailableFunds.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds for settlement");
        }

        var receiverAvailableFunds = fundsRepository.getAvailableFunds(receiverBankCode);

        var senderSettledAvailableFunds = senderAvailableFunds.subtract(amount);
        var receiverSettledAvailableFunds = receiverAvailableFunds.add(amount);

        fundsRepository.updateAvailableFunds(senderBankCode, senderSettledAvailableFunds);
        fundsRepository.updateAvailableFunds(receiverBankCode, receiverSettledAvailableFunds);
    }
}
