package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.SettlementRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;

    public SettlementService(SettlementRepository settlementRepository) {
        this.settlementRepository = settlementRepository;
    }

    @Transactional
    public Optional<PaymentTransaction> tryMakeSettlement(String paymentId) {
        Optional<PaymentTransaction> settledPayment = settlementRepository.settleAcceptedPayment(
                paymentId,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        settledPayment.ifPresent(paymentTransaction -> {
            var amountCents = paymentTransaction.getAmountCents();
            var senderBankCode = Utils.getBankCode(paymentTransaction.getSender());
            var receiverBankCode = Utils.getBankCode(paymentTransaction.getReceiver());

            log.debug("[PIX FLOW - Step 6] Settlement completed in SPI (BCB PI accounts): {} from {} to {}",
                    amountCents, senderBankCode, receiverBankCode);
        });

        return settledPayment;
    }

    @Transactional
    public SettlementBatchResult tryMakeSettlements(List<String> paymentIds) {
        List<PaymentTransaction> settledPayments = settlementRepository.settleAcceptedPayments(
                paymentIds,
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        Set<String> settledPaymentIds = new HashSet<>(settledPayments.size());
        for (PaymentTransaction settledPayment : settledPayments) {
            settledPaymentIds.add(settledPayment.getPaymentId());
        }

        List<String> notSettledPaymentIds = new ArrayList<>(paymentIds.size());
        for (String paymentId : paymentIds) {
            if (!settledPaymentIds.contains(paymentId)) {
                notSettledPaymentIds.add(paymentId);
            }
        }

        log.debug("[PIX FLOW - Step 6] Batch settlement completed in SPI. requested={}, settled={}",
                paymentIds.size(), settledPayments.size());

        return new SettlementBatchResult(settledPayments, notSettledPaymentIds);
    }
}
