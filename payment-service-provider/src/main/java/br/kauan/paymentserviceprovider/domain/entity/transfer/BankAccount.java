package br.kauan.paymentserviceprovider.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccount {
    private String accountNumber;
    private String agencyNumber;
    private BankAccountType type; // "checking", "savings"
    private String bankCode; // ISPB
}