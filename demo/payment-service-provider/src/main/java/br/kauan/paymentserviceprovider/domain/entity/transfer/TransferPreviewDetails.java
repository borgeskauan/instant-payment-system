package br.kauan.paymentserviceprovider.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferPreviewDetails {
    private Party receiver;
}
