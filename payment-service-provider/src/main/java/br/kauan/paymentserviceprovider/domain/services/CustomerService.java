package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginRequest;
import br.kauan.paymentserviceprovider.domain.dto.PixKeyCreationRequest;
import br.kauan.paymentserviceprovider.domain.entity.Customer;
import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import br.kauan.paymentserviceprovider.adapter.output.CustomerRepository;
import br.kauan.paymentserviceprovider.port.output.PixKeyRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Random;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PixKeyRepository pixKeyRepository;

    public CustomerService(CustomerRepository customerRepository, PixKeyRepository pixKeyRepository) {
        this.customerRepository = customerRepository;
        this.pixKeyRepository = pixKeyRepository;
    }

    public Customer loginCustomer(CustomerLoginRequest request) {
        var existingCustomer = customerRepository.findByTaxId(request.getTaxId());
        if (existingCustomer.isPresent()) {
            return existingCustomer.get();
        }

        var generatedBankAccountId = BankAccount.builder().id(BankAccountId.builder()
                        .accountNumber(generateRandomNumberString(8))
                        .agencyNumber(generateRandomNumberString(4))
                        .bankCode(GlobalVariables.getBankCode())
                        .build())
                .type(BankAccountType.CHECKING)
                .build();

        var customerBankAccount = CustomerBankAccount.builder()
                .account(generatedBankAccountId)
                .balance(BigDecimal.valueOf(10000))
                .build();

        var customer = Customer.builder()
                .name(request.getName())
                .taxId(request.getTaxId())
                .bankAccount(customerBankAccount)
                .build();

        return customerRepository.save(customer);
    }

    public void createPixKey(PixKeyCreationRequest request) {
        pixKeyRepository.save(request.getKey(), request.getCustomerId());
    }

    private static String generateRandomNumberString(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }

        Random random = new Random();
        StringBuilder sb = new StringBuilder();

        // First digit can't be zero
        sb.append(random.nextInt(9) + 1);

        // Remaining digits can include zero
        for (int i = 1; i < length; i++) {
            sb.append(random.nextInt(10));
        }

        return sb.toString();
    }
}
