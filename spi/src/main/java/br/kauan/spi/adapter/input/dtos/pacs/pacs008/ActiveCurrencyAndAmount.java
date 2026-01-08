package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveCurrencyAndAmount {

    protected BigDecimal value;

    @JsonProperty(value = "Ccy", required = true)
    protected ActiveCurrencyCode currencyCode;
}
