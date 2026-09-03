package br.kauan.paymentserviceprovider.domain.dto;

import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;

public record CustomerSnapshot(Customer customer, CustomerBankAccount bankAccount) {
}
