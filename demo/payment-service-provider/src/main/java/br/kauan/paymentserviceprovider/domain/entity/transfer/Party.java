package br.kauan.paymentserviceprovider.domain.entity.transfer;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Party {
    private String name;
    private String taxId;

    private BankAccount account;
    private String pixKey;
}