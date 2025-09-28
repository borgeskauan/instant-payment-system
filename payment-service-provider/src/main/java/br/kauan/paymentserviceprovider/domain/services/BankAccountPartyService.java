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
        return Optional.ofNullable(externalPartyRepository.getPartyDetails(pixKey));
    }

    public void addAmountToAccount(BankAccountId bankAccountId, BigDecimal amountToAdd) {
        var bankAccount = customerBankAccountRepository.findById(bankAccountId).orElseThrow();

        var newBalance = bankAccount.getBalance().add(amountToAdd);
        bankAccount.setBalance(newBalance);
        customerBankAccountRepository.save(bankAccount);
    }

    public void removeAmountFromAccount(BankAccountId bankAccountId, BigDecimal amountToAdd) {
        var bankAccount = customerBankAccountRepository.findById(bankAccountId).orElseThrow();

        var newBalance = bankAccount.getBalance().subtract(amountToAdd);
        bankAccount.setBalance(newBalance);
        customerBankAccountRepository.save(bankAccount);
    }
}
