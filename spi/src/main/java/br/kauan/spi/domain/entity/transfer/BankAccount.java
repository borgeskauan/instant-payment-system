package br.kauan.spi.domain.entity.transfer;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccount {
    private String number;
    private String branch;
    private BankAccountType type;
    private String bankCode;
}
