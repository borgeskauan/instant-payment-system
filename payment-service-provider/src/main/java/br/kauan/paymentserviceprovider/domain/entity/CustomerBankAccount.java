package br.kauan.paymentserviceprovider.domain.entity;

import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CustomerBankAccount {
    private String accountNumber;
    private String agencyNumber;
    private BankAccountType type; // "checking", "savings"
    private String bankCode; // ISPB

    private BigDecimal balance;
}
