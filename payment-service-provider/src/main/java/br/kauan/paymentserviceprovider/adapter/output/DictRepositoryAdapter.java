package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.Party;
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

        var bankAccount = BankAccount.builder()
                .bankCode(account.getParticipant())
                .agencyNumber(account.getBranch())
                .accountNumber(account.getNumber())
                .build();

        return Party.builder()
                .name(owner.getName())
                .taxId(owner.getTaxIdNumber())
                .bankAccount(bankAccount)
                .build();
    }
}
