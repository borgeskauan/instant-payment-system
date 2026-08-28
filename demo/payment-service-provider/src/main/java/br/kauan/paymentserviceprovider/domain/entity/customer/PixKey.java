package br.kauan.paymentserviceprovider.domain.entity.customer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PixKey {
    private String customerId;

    private String pixKey;
    private String type;
}
