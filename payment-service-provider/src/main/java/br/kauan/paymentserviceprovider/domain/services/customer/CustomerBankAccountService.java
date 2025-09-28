package br.kauan.paymentserviceprovider.domain.services.customer;

import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import org.springframework.stereotype.Service;

import static br.kauan.paymentserviceprovider.commons.Util.generateRandomNumberString;

@Service
public class CustomerBankAccountService {

    public CustomerBankAccount generateBankAccount() {
        var account = BankAccount.builder()
                .id(buildBankAccountId())
                .type(BankAccountType.CHECKING)
                .build();

        return CustomerBankAccount.builder()
                .account(account)
                .balance(GlobalVariables.getCustomerBankAccountInitialBalance())
                .build();
    }

    private BankAccountId buildBankAccountId() {
        return BankAccountId.builder()
                .accountNumber(generateRandomNumberString(8))
                .agencyNumber(generateRandomNumberString(4))
                .bankCode(GlobalVariables.getBankCode())
                .build();
    }
}