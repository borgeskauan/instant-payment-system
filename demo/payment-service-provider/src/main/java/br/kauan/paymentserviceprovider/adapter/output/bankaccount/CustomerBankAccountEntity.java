package br.kauan.paymentserviceprovider.adapter.output.bankaccount;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBankAccountEntity {

    @EmbeddedId
    private CustomerBankAccountId id;

    private String customerId;

    private String type;

    private BigDecimal balance;

    @Data
    @Builder
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerBankAccountId {
        private String accountNumber;
        private String agencyNumber;
        private String bankCode;
    }
}
