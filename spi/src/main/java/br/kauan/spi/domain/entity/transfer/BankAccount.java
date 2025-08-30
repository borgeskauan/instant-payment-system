package br.kauan.spi.domain.entity.transfer;

import lombok.Data;

@Data
public class BankAccount {
    private Long number;
    private Integer branch;
    private String type; // "checking", "savings"
    private String bankCode; // ISPB
}