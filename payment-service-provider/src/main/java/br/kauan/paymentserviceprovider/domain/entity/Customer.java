package br.kauan.paymentserviceprovider.domain.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Customer {
    private String id;

    private String name;
    private String taxId;

    private CustomerBankAccount bankAccount;
}
