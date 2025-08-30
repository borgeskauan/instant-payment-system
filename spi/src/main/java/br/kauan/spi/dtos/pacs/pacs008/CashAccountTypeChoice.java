package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class CashAccountTypeChoice {

    @JsonPropertyCustom(value = "Cd")
    protected ExternalCashAccountTypeCode accountTypeCode;
}
