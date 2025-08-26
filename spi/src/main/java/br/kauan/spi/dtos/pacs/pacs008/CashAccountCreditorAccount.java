package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CashAccountCreditorAccount {

    @JsonProperty(value = "Id", required = true)
    protected AccountIdentificationChoice id;

    @JsonProperty(value = "Tp", required = true)
    protected CashAccountTypeChoice tp;

    @JsonProperty(value = "Prxy")
    protected ProxyAccountIdentification proxyAccountIdentification;
}
