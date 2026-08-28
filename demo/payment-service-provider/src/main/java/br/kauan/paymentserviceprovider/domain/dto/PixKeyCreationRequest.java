package br.kauan.paymentserviceprovider.domain.dto;

import lombok.Data;

@Data
public class PixKeyCreationRequest {
    private String customerId;
    private String pixKey;
}
