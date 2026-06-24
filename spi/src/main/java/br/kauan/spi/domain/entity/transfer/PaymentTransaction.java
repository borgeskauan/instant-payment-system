package br.kauan.spi.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentTransaction {
    private String paymentId;
    private long amountCents;
    private String currency;
    private String description;
    private Party sender;
    private Party receiver;
}
