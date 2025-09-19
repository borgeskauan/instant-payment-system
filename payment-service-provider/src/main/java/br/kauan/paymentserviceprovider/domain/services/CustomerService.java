package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.entity.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.port.output.BankAccountRepository;
import br.kauan.paymentserviceprovider.port.output.CustomerRepository;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
public class CustomerService {

    private final ExternalPartyRepository externalPartyRepository;
    private final CustomerRepository customerRepository;

    private final BankAccountRepository bankAccountRepository;

    public CustomerService(ExternalPartyRepository externalPartyRepository,
                           CustomerRepository customerRepository,
                           BankAccountRepository bankAccountRepository) {
        this.externalPartyRepository = externalPartyRepository;
        this.customerRepository = customerRepository;
        this.bankAccountRepository = bankAccountRepository;
    }

    public Optional<Party> getInternalCustomerDetails(String customerId) {
        return customerRepository.getCustomerDetails(customerId);
    }

    public Optional<Party> findCustomerDetailsByPixKey(String pixKey) {
        return Optional.ofNullable(externalPartyRepository.getPartyDetails(pixKey));
    }

    public void addAmountToAccount(BankAccountId bankAccountId, BigDecimal amountToAdd) {
        var bankAccount = bankAccountRepository.findById(bankAccountId).orElseThrow();

        var newBalance = bankAccount.getBalance().add(amountToAdd);
        bankAccount.setBalance(newBalance);
        bankAccountRepository.save(bankAccount);
    }

    public void removeAmountFromAccount(BankAccountId bankAccountId, BigDecimal amountToAdd) {
        var bankAccount = bankAccountRepository.findById(bankAccountId).orElseThrow();

        var newBalance = bankAccount.getBalance().subtract(amountToAdd);
        bankAccount.setBalance(newBalance);
        bankAccountRepository.save(bankAccount);
    }
}
