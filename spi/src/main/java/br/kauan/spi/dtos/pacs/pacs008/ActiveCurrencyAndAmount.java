package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ActiveCurrencyAndAmount {

    protected BigDecimal value;

    @JsonPropertyCustom(value = "Ccy", required = true)
    protected ActiveCurrencyCode currencyCode;
}
