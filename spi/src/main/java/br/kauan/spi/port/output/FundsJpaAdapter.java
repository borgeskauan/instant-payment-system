package br.kauan.spi.port.output;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Slf4j
@Repository
public class FundsJpaAdapter implements FundsRepository {

    private final FundsJpaClient fundsJpaClient;

    public FundsJpaAdapter(FundsJpaClient fundsJpaClient) {
        this.fundsJpaClient = fundsJpaClient;
    }

    @Override
    public BigDecimal createAccountIfNotExists(String bankCode, BigDecimal amount) {
        var ops = fundsJpaClient.findById(bankCode);
        if (ops.isPresent()) {
            return ops.get().getBalance();
        }

        var newEntity = new FundsEntity();
        newEntity.setBankCode(bankCode);
        newEntity.setBalance(amount);

        try {
            fundsJpaClient.save(newEntity);
            return newEntity.getBalance();
        } catch (Exception e) {
            log.warn("An error ocurred when creating funds entity for {}", bankCode, e);

            throw e;
        }
    }

    @Override
    public BigDecimal getAvailableFunds(String bankCode) {
        return createAccountIfNotExists(bankCode, BigDecimal.ZERO);
    }

    @Override
    public void deductFunds(String bankCode, BigDecimal amount) {
        int updatedRows = fundsJpaClient.deductFunds(bankCode, amount);
        if (updatedRows == 0) {
            throw new RuntimeException("There was an error updating the balance for the given account");
        }
    }

    @Override
    public void addFunds(String bankCode, BigDecimal amount) {
        int updatedRows = fundsJpaClient.addFunds(bankCode, amount);
        if (updatedRows == 0) {
            throw new RuntimeException("There was an error updating the balance for the given account");
        }
    }
}
