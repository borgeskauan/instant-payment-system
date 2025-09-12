package br.kauan.paymentserviceprovider.domain.dto;

import lombok.Data;

@Data
public class TransferPreviewRequest {
    private String receiverPixKey;
}
