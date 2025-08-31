package br.kauan.spi.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccount {
    private Long number;
    private Integer branch;
    private BankAccountType type; // "checking", "savings"
    private String bankCode; // ISPB
}