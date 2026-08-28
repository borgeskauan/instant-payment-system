package br.kauan.paymentserviceprovider.domain.dto;

import lombok.Data;

@Data
public class CustomerLoginRequest {
    private String name;
    private String taxId;
}
