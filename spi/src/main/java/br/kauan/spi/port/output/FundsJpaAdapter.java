package br.kauan.spi.port.output;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class FundsJpaAdapter implements FundsRepository {

    private final FundsJpaClient fundsJpaClient;

    public FundsJpaAdapter(FundsJpaClient fundsJpaClient) {
        this.fundsJpaClient = fundsJpaClient;
    }

    @Override
    public void provisionAccount(String bankCode, BigDecimal balance, boolean resetIfExists) {
        var existing = fundsJpaClient.findById(bankCode);
        if (existing.isPresent()) {
            if (resetIfExists) {
                var entity = existing.get();
                entity.setBalance(balance);
                fundsJpaClient.save(entity);
            }
            return;
        }

        var newEntity = new FundsEntity();
        newEntity.setBankCode(bankCode);
        newEntity.setBalance(balance);
        fundsJpaClient.save(newEntity);
    }

    @Override
    public BigDecimal getAvailableFunds(String bankCode) {
        return fundsJpaClient.findById(bankCode)
                .map(FundsEntity::getBalance)
                .orElseThrow(() -> new IllegalStateException("Settlement account not found"));
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
