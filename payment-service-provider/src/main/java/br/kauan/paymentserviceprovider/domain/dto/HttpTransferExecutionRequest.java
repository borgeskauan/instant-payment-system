package br.kauan.paymentserviceprovider.domain.dto;

import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class HttpTransferExecutionRequest {
    private String senderCustomerId;

    private Party receiver;

    private BigDecimal amount;
    private String description;
}