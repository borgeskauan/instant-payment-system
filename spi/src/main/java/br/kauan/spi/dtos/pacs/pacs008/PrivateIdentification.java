package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PrivateIdentification {

    @JsonPropertyCustom(value = "PrvtId", required = true)
    protected PersonIdentification personIdentification;
}
