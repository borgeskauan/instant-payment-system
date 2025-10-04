package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.FundsRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class SettlementService {

    @Value("${spi.default-initial-balance}")
    private BigDecimal defaultInitialBalance;

    private final FundsRepository fundsRepository;

    public SettlementService(FundsRepository fundsRepository) {
        this.fundsRepository = fundsRepository;
    }

    @Transactional
    public void makeSettlement(PaymentTransaction paymentTransaction) {
        var amount = paymentTransaction.getAmount();
        var senderBankCode = Utils.getBankCode(paymentTransaction.getSender());
        var receiverBankCode = Utils.getBankCode(paymentTransaction.getReceiver());

        createAccountsIfNotExists(senderBankCode, receiverBankCode);

        var senderAvailableFunds = fundsRepository.getAvailableFunds(senderBankCode);
        if (senderAvailableFunds.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds for settlement");
        }

        fundsRepository.deductFunds(senderBankCode, amount);
        fundsRepository.addFunds(receiverBankCode, amount);

//        var senderSettledAvailableFunds = fundsRepository.getAvailableFunds(senderBankCode);
//        var receiverSettledAvailableFunds = fundsRepository.getAvailableFunds(receiverBankCode);
//
//        log.info("Settlement completed: {} from {} to {}", amount, senderBankCode, receiverBankCode);
//        log.info("New available funds - {}: {}, {}: {}", senderBankCode, senderSettledAvailableFunds, receiverBankCode, receiverSettledAvailableFunds);
    }

    private void createAccountsIfNotExists(String senderBankCode, String receiverBankCode) {
        fundsRepository.createAccountIfNotExists(senderBankCode, defaultInitialBalance);
        fundsRepository.createAccountIfNotExists(receiverBankCode, defaultInitialBalance);
    }
}
