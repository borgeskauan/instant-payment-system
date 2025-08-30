package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class RemittanceInformation {

    @JsonPropertyCustom(value = "Ustrd")
    protected String additionalInformation;
}
