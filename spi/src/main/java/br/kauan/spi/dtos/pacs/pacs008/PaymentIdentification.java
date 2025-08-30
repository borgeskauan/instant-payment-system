package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PaymentIdentification {

    @JsonPropertyCustom(value = "EndToEndId", required = true)
    protected String endToEndId;
}
