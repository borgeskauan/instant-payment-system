package br.kauan.paymentserviceprovider.adapter.output.dict;

import br.kauan.paymentserviceprovider.config.PspProperties;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DictClient {

    private final RestClient restClient;
    private final PspProperties properties;

    public DictClient(RestClient.Builder builder, @Value("${external.dict.url}") String baseUrl, PspProperties properties) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.properties = properties;
    }

    public Party resolve(String pixKey) {
        DirectoryEntry response = restClient.get()
                .uri("/keys/{pixKey}", pixKey)
                .retrieve()
                .body(DirectoryEntry.class);
        if (response == null) {
            throw new IllegalStateException("DICT returned an empty response");
        }

        Account account = response.account();
        Owner owner = response.owner();
        BankAccountId accountId = BankAccountId.builder()
                .bankCode(account.participant())
                .agencyNumber(account.branch())
                .accountNumber(account.number())
                .build();
        BankAccount bankAccount = BankAccount.builder()
                .id(accountId)
                .type(BankAccountType.valueOf(account.type()))
                .build();
        return Party.builder()
                .name(owner.name())
                .taxId(owner.taxIdNumber())
                .account(bankAccount)
                .pixKey(response.key())
                .build();
    }

    public void register(PixKey pixKey, Customer customer, CustomerBankAccount bankAccount) {
        DirectoryEntry request = new DirectoryEntry(
                pixKey.getPixKey(),
                new Account(
                        properties.bankCode(),
                        bankAccount.getAccount().getId().getAgencyNumber(),
                        bankAccount.getAccount().getId().getAccountNumber(),
                        bankAccount.getAccount().getType().name()
                ),
                new Owner(customer.getTaxId(), customer.getName())
        );
        restClient.post()
                .uri("/keys")
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private record DirectoryEntry(String key, Account account, Owner owner) {
    }

    private record Account(String participant, String branch, String number, String type) {
    }

    private record Owner(String taxIdNumber, String name) {
    }
}
