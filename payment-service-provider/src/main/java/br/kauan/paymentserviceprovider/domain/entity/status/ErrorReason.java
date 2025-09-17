package br.kauan.paymentserviceprovider.domain.entity.status;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ErrorReason {
    private String errorCode; // "AB03"
    private List<String> descriptions; // "Invalid Creditor Account Number"
}
