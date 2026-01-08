package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.customer.CustomerRepository;
import br.kauan.paymentserviceprovider.domain.entity.mappers.CustomerPartyMapper;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.port.output.CustomerBankAccountRepository;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
        var bankAccounts = customerBankAccountRepository.findByCustomerId(customerId).getFirst();
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

    public void addAmountToAccount(BankAccountId bankAccountId, BigDecimal amountToAdd) {
        var bankAccount = customerBankAccountRepository.findById(bankAccountId).orElseThrow();
        var oldBalance = bankAccount.getBalance();

        var newBalance = bankAccount.getBalance().add(amountToAdd);
        bankAccount.setBalance(newBalance);
        customerBankAccountRepository.save(bankAccount);
        
        log.info("[PIX FLOW - Settlement] Credited amount {} to account {}. Balance: {} -> {}", 
                amountToAdd, bankAccountId, oldBalance, newBalance);
    }

    public void removeAmountFromAccount(BankAccountId bankAccountId, BigDecimal amountToAdd) {
        var bankAccount = customerBankAccountRepository.findById(bankAccountId).orElseThrow();
        var oldBalance = bankAccount.getBalance();

        var newBalance = bankAccount.getBalance().subtract(amountToAdd);
        bankAccount.setBalance(newBalance);
        customerBankAccountRepository.save(bankAccount);
        
        log.info("[PIX FLOW - Settlement] Debited amount {} from account {}. Balance: {} -> {}", 
                amountToAdd, bankAccountId, oldBalance, newBalance);
    }
}
