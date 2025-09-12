package br.kauan.paymentserviceprovider.domain.dto;

import br.kauan.paymentserviceprovider.domain.entity.Party;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferExecutionRequest {
    // The APP shouldn't know the sender details, only the PSP. The APP should provide only an identifier for the sender (e.g., senderId).
    private String senderCustomerId;

    private Party receiver;

    private BigDecimal amount;
}