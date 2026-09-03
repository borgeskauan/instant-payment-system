package br.kauan.paymentserviceprovider.state;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PspStateStore {

    private final Map<String, Customer> customersById = new HashMap<>();
    private final Map<String, String> customerIdsByTaxId = new HashMap<>();
    private final Map<String, CustomerBankAccount> accountsByCustomerId = new HashMap<>();
    private final Map<BankAccountId, CustomerBankAccount> accountsById = new HashMap<>();
    private final Map<String, List<PixKey>> pixKeysByCustomerId = new HashMap<>();

    public synchronized Optional<Customer> findCustomerByTaxId(String taxId) {
        return Optional.ofNullable(customerIdsByTaxId.get(taxId)).map(customersById::get);
    }

    public synchronized Optional<Customer> findCustomerById(String customerId) {
        return Optional.ofNullable(customersById.get(customerId));
    }

    public synchronized Optional<CustomerBankAccount> findAccountByCustomerId(String customerId) {
        return Optional.ofNullable(accountsByCustomerId.get(customerId));
    }

    public synchronized void addCustomer(Customer customer, CustomerBankAccount account) {
        customersById.put(customer.getId(), customer);
        customerIdsByTaxId.put(customer.getTaxId(), customer.getId());
        accountsByCustomerId.put(customer.getId(), account);
        accountsById.put(account.getAccount().getId(), account);
    }

    public synchronized void addPixKey(PixKey pixKey) {
        pixKeysByCustomerId
                .computeIfAbsent(pixKey.getCustomerId(), ignored -> new ArrayList<>())
                .add(pixKey);
    }

    public synchronized List<PixKey> findPixKeysByCustomerId(String customerId) {
        return List.copyOf(pixKeysByCustomerId.getOrDefault(customerId, List.of()));
    }

    public synchronized void applyBalanceDeltas(Map<BankAccountId, BigDecimal> deltasByAccount) {
        Map<BankAccountId, CustomerBankAccount> affectedAccounts = new HashMap<>(deltasByAccount.size());
        for (BankAccountId accountId : deltasByAccount.keySet()) {
            CustomerBankAccount account = accountsById.get(accountId);
            if (account == null) {
                throw new IllegalArgumentException("Local account not found: " + accountId);
            }
            affectedAccounts.put(accountId, account);
        }

        for (Map.Entry<BankAccountId, BigDecimal> entry : deltasByAccount.entrySet()) {
            CustomerBankAccount account = affectedAccounts.get(entry.getKey());
            account.setBalance(account.getBalance().add(entry.getValue()));
        }
    }
}
