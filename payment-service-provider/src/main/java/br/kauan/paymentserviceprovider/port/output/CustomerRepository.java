package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;

import java.util.Optional;

public interface CustomerRepository {
    Optional<Party> getCustomerDetails(String customerId);
}
