package br.kauan.paymentserviceprovider.domain.dto;

import java.math.BigDecimal;

public record TransferExecutionRequest(
        String senderCustomerId,
        String receiverPixKey,
        BigDecimal amount,
        String description
) {
}
