package br.kauan.paymentserviceprovider.adapter.output.dict;

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
