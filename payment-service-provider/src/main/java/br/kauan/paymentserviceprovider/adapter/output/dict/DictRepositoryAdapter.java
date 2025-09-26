package br.kauan.paymentserviceprovider.adapter.output.dict;

import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.Customer;
import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.PixKey;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import org.springframework.stereotype.Repository;

@Repository
public class DictRepositoryAdapter implements ExternalPartyRepository {

    private final DictClient dictClient;

    public DictRepositoryAdapter(DictClient dictClient) {
        this.dictClient = dictClient;
    }

    @Override
    public Party getPartyDetails(String receiverPixKey) {
        var response = dictClient.getPartyDetails(receiverPixKey);

        return convertToDomain(response);
    }

    @Override
    public void createPixKey(PixKey pixKey, Customer customer, CustomerBankAccount customerBankAccount) {
        var request = DictPixKeyCreationRequest.builder()
                .key(pixKey.getPixKey())
                .keyType(pixKey.getType())
                .account(Account.builder()
                        .participant(GlobalVariables.getBankCode())
                        .branch(customerBankAccount.getAccount().getId().getAgencyNumber())
                        .number(customerBankAccount.getAccount().getId().getAccountNumber())
                        .type(BankAccountType.CHECKING.name())
                        .build())
                .owner(Owner.builder()
                        .name(customer.getName())
                        .taxIdNumber(customer.getTaxId())
                        .build())
                .build();

        dictClient.createPixKey(request);
    }

    private Party convertToDomain(DictResponse response) {
        var owner = response.getOwner();
        var account = response.getAccount();

        var bankAccountId = BankAccountId.builder()
                .bankCode(account.getParticipant())
                .agencyNumber(account.getBranch())
                .accountNumber(account.getNumber())
                .build();

        var bankAccount = BankAccount.builder()
                .id(bankAccountId)
                .type(BankAccountType.valueOf(account.getType()))
                .build();

        return Party.builder()
                .name(owner.getName())
                .taxId(owner.getTaxIdNumber())
                .account(bankAccount)
                .build();
    }
}
