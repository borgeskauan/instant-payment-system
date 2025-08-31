package br.kauan.spi.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PaymentTransaction {
    private String paymentId;
    private BigDecimal amount;
    private String currency;
    private String description;
    private Party sender;
    private Party receiver;
}