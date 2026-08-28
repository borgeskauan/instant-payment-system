package br.kauan.paymentserviceprovider.domain.entity.status;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StatusReport {
    private String originalPaymentId;
    private PaymentStatus status;
}
