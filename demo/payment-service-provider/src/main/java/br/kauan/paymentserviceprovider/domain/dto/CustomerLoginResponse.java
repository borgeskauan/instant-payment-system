package br.kauan.paymentserviceprovider.domain.dto;

import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerLoginResponse {

    private Customer customer;
    private CustomerBankAccount bankAccount;
}
