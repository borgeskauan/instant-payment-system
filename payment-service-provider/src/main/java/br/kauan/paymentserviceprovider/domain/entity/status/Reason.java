package br.kauan.paymentserviceprovider.domain.entity.status;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Reason {
    private String code; // "AB03"
    private List<String> descriptions; // "Invalid Creditor Account Number"
}
