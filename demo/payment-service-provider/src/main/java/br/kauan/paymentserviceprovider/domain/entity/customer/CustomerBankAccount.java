package br.kauan.paymentserviceprovider.domain.entity.customer;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CustomerBankAccount {
    private String customerId;

    private BankAccount account;
    private BigDecimal balance;
}
