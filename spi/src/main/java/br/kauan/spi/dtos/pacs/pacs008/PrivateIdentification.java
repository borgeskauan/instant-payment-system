package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class PrivateIdentification {

    @JsonPropertyCustom(value = "PrvtId", required = true)
    protected PersonIdentification personIdentification;
}
