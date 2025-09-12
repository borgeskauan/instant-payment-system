package br.kauan.paymentserviceprovider.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Party {
    private String name;
    private String taxId;

    private BankAccount account;
    private String pixKey;
}