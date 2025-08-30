package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class CashAccountCreditorAccount {

    @JsonPropertyCustom(value = "Id", required = true)
    protected AccountIdentificationChoice id;

    @JsonPropertyCustom(value = "Tp", required = true)
    protected CashAccountTypeChoice accountType;

    @JsonPropertyCustom(value = "Prxy")
    protected ProxyAccountIdentification proxyAccountIdentification;
}
