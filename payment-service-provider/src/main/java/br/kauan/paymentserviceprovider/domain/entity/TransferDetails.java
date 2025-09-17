package br.kauan.paymentserviceprovider.domain.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferDetails {
    private String transferId;
}
