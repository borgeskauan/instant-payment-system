package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.FundsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    @Value("${spi.default-initial-balance}")
    private BigDecimal defaultInitialBalance;

    private final FundsRepository fundsRepository;

    public Mono<Void> makeSettlement(PaymentTransaction paymentTransaction) {
        var amount = paymentTransaction.getAmount();
        var senderBankCode = Utils.getBankCode(paymentTransaction.getSender());
        var receiverBankCode = Utils.getBankCode(paymentTransaction.getReceiver());

        return createAccountsIfNotExists(senderBankCode, receiverBankCode)
                .then(Mono.defer(() -> fundsRepository.getAvailableFunds(senderBankCode)))
                .flatMap(senderAvailableFunds -> {
                    if (senderAvailableFunds.compareTo(amount) < 0) {
                        return Mono.error(new IllegalStateException("Insufficient funds for settlement"));
                    }
                    return fundsRepository.deductFunds(senderBankCode, amount)
                            .then(fundsRepository.addFunds(receiverBankCode, amount));
                });
    }

    private Mono<Void> createAccountsIfNotExists(String senderBankCode, String receiverBankCode) {
        return fundsRepository.createAccountIfNotExists(senderBankCode, defaultInitialBalance)
                .then(fundsRepository.createAccountIfNotExists(receiverBankCode, defaultInitialBalance))
                .then();
    }
}