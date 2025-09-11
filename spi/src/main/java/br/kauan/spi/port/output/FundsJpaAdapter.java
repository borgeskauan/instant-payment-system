package br.kauan.spi.port.output;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class FundsJpaAdapter implements FundsRepository {

    @Value("${spi.default-initial-balance}")
    private BigDecimal defaultInitialBalance;

    private final FundsJpaClient fundsJpaClient;

    public FundsJpaAdapter(FundsJpaClient fundsJpaClient) {
        this.fundsJpaClient = fundsJpaClient;
    }

    @Override
    public BigDecimal getAvailableFunds(String senderBankCode) {
        return fundsJpaClient.findById(senderBankCode)
                .map(FundsEntity::getBalance)
                .orElse(defaultInitialBalance);
    }

    @Override
    public void updateAvailableFunds(String senderBankCode, BigDecimal funds) {
        var fundsEntity = fundsJpaClient.findById(senderBankCode)
                .orElseGet(() -> {
                    var newEntity = new FundsEntity();
                    newEntity.setBankCode(senderBankCode);
                    return newEntity;
                });

        fundsEntity.setBalance(funds);
        fundsJpaClient.save(fundsEntity);
    }
}
