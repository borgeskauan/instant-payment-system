package br.kauan.paymentserviceprovider.domain.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Party {
    private String name;
    private String taxId;

    private BankAccount bankAccount;
}
