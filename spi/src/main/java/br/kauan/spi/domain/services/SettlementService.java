package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.FundsRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SettlementService {

    private final FundsRepository fundsRepository;

    public SettlementService(FundsRepository fundsRepository) {
        this.fundsRepository = fundsRepository;
    }

    @Transactional
    public void makeSettlement(PaymentTransaction paymentTransaction) {
        var amount = paymentTransaction.getAmount();
        var senderBankCode = Utils.getBankCode(paymentTransaction.getSender());
        var receiverBankCode = Utils.getBankCode(paymentTransaction.getReceiver());

        fundsRepository.deductFunds(senderBankCode, amount);
        fundsRepository.addFunds(receiverBankCode, amount);

        log.debug("[PIX FLOW - Step 6] Settlement completed in SPI (BCB PI accounts): {} from {} to {}", 
                amount, senderBankCode, receiverBankCode);
    }
}
