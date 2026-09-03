package br.kauan.paymentserviceprovider.domain.entity.commons;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccountId {
    private String accountNumber;
    private String agencyNumber;
    private String bankCode; // ISPB
}
