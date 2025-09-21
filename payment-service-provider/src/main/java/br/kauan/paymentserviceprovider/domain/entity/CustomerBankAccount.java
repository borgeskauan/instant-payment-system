package br.kauan.paymentserviceprovider.domain.entity;

import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CustomerBankAccount {
    private BankAccount account;
    private BigDecimal balance;
}
