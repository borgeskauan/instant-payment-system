package br.kauan.paymentserviceprovider.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccount {
    private BankAccountId id;

    private BankAccountType type; // "checking", "savings"
}