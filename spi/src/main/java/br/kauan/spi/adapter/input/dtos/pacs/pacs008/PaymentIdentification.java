package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentIdentification {

    @JsonProperty(value = "EndToEndId", required = true)
    protected String endToEndId;
}
