package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CashAccountDebtorAccount {

    @JsonProperty(value = "Id", required = true)
    protected AccountIdentificationChoice id;

    @JsonProperty(value = "Tp", required = true)
    protected CashAccountTypeChoice accountType;
}
