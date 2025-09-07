package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentIdentification {

    @JsonPropertyCustom(value = "EndToEndId", required = true)
    protected String endToEndId;
}
