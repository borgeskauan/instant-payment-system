package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;

public interface ExternalPartyRepository {
    Party getPartyDetails(String receiverPixKey);
}
