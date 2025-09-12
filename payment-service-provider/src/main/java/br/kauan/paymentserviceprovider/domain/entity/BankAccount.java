package br.kauan.paymentserviceprovider.domain.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccount {

    private String bankCode;
    private String agencyNumber;
    private String accountNumber;
}
