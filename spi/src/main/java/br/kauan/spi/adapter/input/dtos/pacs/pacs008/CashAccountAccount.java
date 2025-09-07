package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CashAccountAccount {

    @JsonPropertyCustom(value = "Id", required = true)
    protected AccountIdentificationChoice id;

    @JsonPropertyCustom(value = "Tp", required = true)
    protected CashAccountTypeChoice accountType;

    @JsonPropertyCustom(value = "Prxy")
    protected ProxyAccountIdentification proxyAccountIdentification;
}
