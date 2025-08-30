package br.kauan.spi.domain.entity.transfer;

import lombok.Data;

@Data
public class PaymentTransaction {
    private String paymentId;
    private Double amount;
    private String currency;
    private String description;
    private Party sender;
    private Party receiver;
}