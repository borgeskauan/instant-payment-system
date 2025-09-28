package br.kauan.paymentserviceprovider.domain.entity.customer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Customer {
    private String id;

    private String name;
    private String taxId;
}
