package br.kauan.spi.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentTransactionCommand {
    private String paymentId;
    private long amountCents;
    private String currency;
    private String description;
    private Party sender;
    private Party receiver;

    public String senderIspb() {
        return bankCode(sender);
    }

    public String receiverIspb() {
        return bankCode(receiver);
    }

    private String bankCode(Party party) {
        return party == null || party.getAccount() == null
                ? null
                : party.getAccount().getBankCode();
    }
}
