package br.kauan.spi.domain.entity.transfer;

import lombok.Data;

@Data
public class Party {
    private String name;
    private String taxId;
    private BankAccount account;
    private String pixKey;

}