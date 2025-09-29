package br.kauan.paymentserviceprovider.domain.services.customer;

import br.kauan.paymentserviceprovider.adapter.output.customer.CustomerRepository;
import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginRequest;
import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginResponse;
import br.kauan.paymentserviceprovider.domain.dto.PixKeyCreationRequest;
import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import br.kauan.paymentserviceprovider.port.output.CustomerBankAccountRepository;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import br.kauan.paymentserviceprovider.port.output.PixKeyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class CustomerService {

    private static final String DEFAULT_PIX_KEY_TYPE = "EMAIL";

    private final CustomerRepository customerRepository;
    private final PixKeyRepository pixKeyRepository;
    private final ExternalPartyRepository externalPartyRepository;
    private final CustomerBankAccountRepository customerBankAccountRepository;
    private final CustomerBankAccountService customerBankAccountService;

    public CustomerService(
            CustomerRepository customerRepository,
            PixKeyRepository pixKeyRepository,
            ExternalPartyRepository externalPartyRepository,
            CustomerBankAccountRepository customerBankAccountRepository,
            CustomerBankAccountService customerBankAccountService
    ) {
        this.customerRepository = customerRepository;
        this.pixKeyRepository = pixKeyRepository;
        this.externalPartyRepository = externalPartyRepository;
        this.customerBankAccountRepository = customerBankAccountRepository;
        this.customerBankAccountService = customerBankAccountService;
    }

    @Transactional
    public CustomerLoginResponse loginCustomer(CustomerLoginRequest request) {
        log.info("Attempting to login customer with taxId: {}", request.getTaxId());

        return customerRepository.findByTaxId(request.getTaxId())
                .map(this::handleExistingCustomer)
                .orElseGet(() -> createNewCustomer(request));
    }

    @Transactional
    public void createPixKey(PixKeyCreationRequest request) {
        log.info("Creating PIX key for customer: {}", request.getCustomerId());

        var customer = findCustomerById(request.getCustomerId());
        var customerBankAccount = findCustomerBankAccount(request.getCustomerId());

        var pixKey = buildPixKey(request, customer);

        externalPartyRepository.createPixKey(pixKey, customer, customerBankAccount);
        pixKeyRepository.save(pixKey);

        log.info("PIX key created successfully for customer: {}", request.getCustomerId());
    }

    public List<PixKey> getAllPixKeys(String customerId) {
        log.debug("Retrieving all PIX keys for customer: {}", customerId);
        return pixKeyRepository.findAllByCustomerId(customerId);
    }

    private CustomerLoginResponse handleExistingCustomer(Customer customer) {
        var bankAccount = findCustomerBankAccount(customer.getId());

        return CustomerLoginResponse.builder()
                .customer(customer)
                .bankAccount(bankAccount)
                .build();
    }

    private CustomerLoginResponse createNewCustomer(CustomerLoginRequest request) {
        log.info("Creating new customer with taxId: {}", request.getTaxId());

        var customerBankAccount = customerBankAccountService.generateBankAccount();
        var customer = buildCustomer(request);

        Customer savedCustomer = customerRepository.save(customer);

        customerBankAccount.setCustomerId(savedCustomer.getId());
        customerBankAccountRepository.save(customerBankAccount);

        log.info("New customer created successfully with ID: {}", savedCustomer.getId());
        return CustomerLoginResponse.builder()
                .customer(savedCustomer)
                .bankAccount(customerBankAccount)
                .build();
    }

    private Customer findCustomerById(String customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found with ID: " + customerId));
    }

    private CustomerBankAccount findCustomerBankAccount(String customerId) {
        return customerBankAccountRepository.findByCustomerId(customerId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Customer has no bank account."));
    }

    private PixKey buildPixKey(PixKeyCreationRequest request, Customer customer) {
        return PixKey.builder()
                .pixKey(request.getPixKey())
                .customerId(customer.getId())
                .type(DEFAULT_PIX_KEY_TYPE)
                .build();
    }

    private Customer buildCustomer(CustomerLoginRequest request) {
        return Customer.builder()
                .name(request.getName())
                .taxId(request.getTaxId())
                .build();
    }
}