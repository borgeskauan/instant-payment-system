package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import br.kauan.spi.adapter.input.dtos.pacs.pacs008.ActiveCurrencyCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ActiveCurrencyAndAmount {

    protected BigDecimal value;

    @JsonProperty(value = "Ccy", required = true)
    protected ActiveCurrencyCode currencyCode;
}
