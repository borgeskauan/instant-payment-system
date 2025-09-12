package br.kauan.paymentserviceprovider.domain.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferPreviewDetails {
    private Party receiver;
}
