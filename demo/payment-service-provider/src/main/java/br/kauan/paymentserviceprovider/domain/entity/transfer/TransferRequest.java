package br.kauan.paymentserviceprovider.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TransferRequest {
    private Party sender;
    private Party receiver;

    private BigDecimal amount;
    private String description;
}
