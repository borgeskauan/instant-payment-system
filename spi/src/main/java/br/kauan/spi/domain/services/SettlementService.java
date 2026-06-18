package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.SettlementRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;

    public SettlementService(SettlementRepository settlementRepository) {
        this.settlementRepository = settlementRepository;
    }

    @Transactional
    public boolean tryMakeSettlement(PaymentTransaction paymentTransaction) {
        var amount = paymentTransaction.getAmount();
        var senderBankCode = Utils.getBankCode(paymentTransaction.getSender());
        var receiverBankCode = Utils.getBankCode(paymentTransaction.getReceiver());

        boolean settled = settlementRepository.settleAcceptedPayment(
                paymentTransaction.getPaymentId(),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        if (settled) {
            log.debug("[PIX FLOW - Step 6] Settlement completed in SPI (BCB PI accounts): {} from {} to {}",
                    amount, senderBankCode, receiverBankCode);
        }

        return settled;
    }
}
