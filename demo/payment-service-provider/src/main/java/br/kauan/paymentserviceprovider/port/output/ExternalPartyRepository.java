package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;

public interface ExternalPartyRepository {
    Party getPartyDetails(String receiverPixKey);

    void createPixKey(PixKey pixKey, Customer customer, CustomerBankAccount customerBankAccount);
}
