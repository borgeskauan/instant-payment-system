package br.kauan.paymentserviceprovider.domain.services.customer;

import br.kauan.paymentserviceprovider.adapter.output.dict.DictClient;
import br.kauan.paymentserviceprovider.config.PspProperties;
import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginRequest;
import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginResponse;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static br.kauan.paymentserviceprovider.commons.Util.generateRandomNumberString;

@Slf4j
@Service
public class CustomerService {

    private final PspStateStore stateStore;
    private final DictClient dictClient;
    private final PspProperties properties;

    public CustomerService(
            PspStateStore stateStore,
            DictClient dictClient,
            PspProperties properties
    ) {
        this.stateStore = stateStore;
        this.dictClient = dictClient;
        this.properties = properties;
    }

    public synchronized CustomerLoginResponse findOrCreateCustomer(CustomerLoginRequest request) {
        log.info("Finding or creating demo customer with taxId: {}", request.getTaxId());

        return stateStore.findCustomerByTaxId(request.getTaxId())
                .map(this::handleExistingCustomer)
                .orElseGet(() -> createNewCustomer(request));
    }

    public void createPixKey(String customerId, String pixKeyValue) {
        log.info("Creating PIX key for customer: {}", customerId);

        var customer = findCustomerById(customerId);
        var customerBankAccount = findCustomerBankAccount(customerId);

        var pixKey = PixKey.builder().pixKey(pixKeyValue).customerId(customerId).build();

        dictClient.register(pixKey, customer, customerBankAccount);
        stateStore.addPixKey(pixKey);

        log.info("PIX key created successfully for customer: {}", customerId);
    }

    public List<PixKey> getAllPixKeys(String customerId) {
        log.debug("Retrieving all PIX keys for customer: {}", customerId);
        return stateStore.findPixKeysByCustomerId(customerId);
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

        Customer customer = buildCustomer(request);
        CustomerBankAccount customerBankAccount = generateBankAccount(customer.getId());
        stateStore.addCustomer(customer, customerBankAccount);

        log.info("New customer created successfully with ID: {}", customer.getId());
        return CustomerLoginResponse.builder()
                .customer(customer)
                .bankAccount(customerBankAccount)
                .build();
    }

    private Customer findCustomerById(String customerId) {
        return stateStore.findCustomerById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found with ID: " + customerId));
    }

    private CustomerBankAccount findCustomerBankAccount(String customerId) {
        return stateStore.findAccountByCustomerId(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer has no bank account."));
    }

    private Customer buildCustomer(CustomerLoginRequest request) {
        return Customer.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .taxId(request.getTaxId())
                .build();
    }

    private CustomerBankAccount generateBankAccount(String customerId) {
        BankAccountId accountId = BankAccountId.builder()
                .accountNumber(generateRandomNumberString(8))
                .agencyNumber(generateRandomNumberString(4))
                .bankCode(properties.bankCode())
                .build();
        BankAccount account = BankAccount.builder()
                .id(accountId)
                .type(BankAccountType.CHECKING)
                .build();
        return CustomerBankAccount.builder()
                .customerId(customerId)
                .account(account)
                .balance(properties.initialBalance())
                .build();
    }
}
