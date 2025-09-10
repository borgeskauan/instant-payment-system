package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CashAccountTypeChoice {

    @JsonProperty(value = "Cd")
    protected ExternalCashAccountTypeCode accountTypeCode;
}
