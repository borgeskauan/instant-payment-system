package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.customer.CustomerRepository;
import br.kauan.paymentserviceprovider.domain.entity.mappers.CustomerPartyMapper;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.port.output.CustomerBankAccountRepository;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class BankAccountPartyService {

    private final ExternalPartyRepository externalPartyRepository;
    private final CustomerRepository customerRepository;

    private final CustomerBankAccountRepository customerBankAccountRepository;

    private final CustomerPartyMapper customerPartyMapper;

    public BankAccountPartyService(
            ExternalPartyRepository externalPartyRepository,
            CustomerRepository customerRepository,
            CustomerBankAccountRepository customerBankAccountRepository,
            CustomerPartyMapper customerPartyMapper
    ) {
        this.externalPartyRepository = externalPartyRepository;
        this.customerRepository = customerRepository;
        this.customerBankAccountRepository = customerBankAccountRepository;
        this.customerPartyMapper = customerPartyMapper;
    }

    public Optional<Party> getInternalPartyDetails(String customerId) {
        var customer = customerRepository.findById(customerId);
        var bankAccounts = customerBankAccountRepository.findAllByCustomerIds(List.of(customerId)).getFirst();
        return customer.map(c -> customerPartyMapper.toParty(c, bankAccounts));
    }

    public Optional<Party> findPartyDetailsByPixKey(String pixKey) {
        log.info("[PIX FLOW - DICT Query] Querying DICT for PIX key: {}", pixKey);
        var partyDetails = Optional.ofNullable(externalPartyRepository.getPartyDetails(pixKey));
        
        if (partyDetails.isPresent()) {
            log.info("[PIX FLOW - DICT Query] PIX key found. Bank: {}, Account holder: {}", 
                    partyDetails.get().getAccount().getId().getBankCode(), partyDetails.get().getName());
        } else {
            log.warn("[PIX FLOW - DICT Query] PIX key not found: {}", pixKey);
        }
        
        return partyDetails;
    }

    public void addAmountsToAccounts(Map<BankAccountId, BigDecimal> amountsByAccount) {
        updateAccountBalances(amountsByAccount);
    }

    public void removeAmountsFromAccounts(Map<BankAccountId, BigDecimal> amountsByAccount) {
        Map<BankAccountId, BigDecimal> negativeAmountsByAccount = new HashMap<>(amountsByAccount.size());
        for (Map.Entry<BankAccountId, BigDecimal> entry : amountsByAccount.entrySet()) {
            negativeAmountsByAccount.put(entry.getKey(), entry.getValue().negate());
        }
        updateAccountBalances(negativeAmountsByAccount);
    }

    private void updateAccountBalances(Map<BankAccountId, BigDecimal> balanceDeltasByAccount) {
        if (balanceDeltasByAccount.isEmpty()) {
            return;
        }

        Collection<CustomerBankAccount> bankAccounts = customerBankAccountRepository.findAllByIds(balanceDeltasByAccount.keySet());
        for (CustomerBankAccount bankAccount : bankAccounts) {
            BankAccountId bankAccountId = bankAccount.getAccount().getId();
            BigDecimal balanceDelta = balanceDeltasByAccount.get(bankAccountId);
            if (balanceDelta == null) {
                continue;
            }

            bankAccount.setBalance(bankAccount.getBalance().add(balanceDelta));
        }

        customerBankAccountRepository.saveAll(bankAccounts);
        log.info("[PIX FLOW - Settlement] Updated balances for {} accounts", bankAccounts.size());
    }
}
