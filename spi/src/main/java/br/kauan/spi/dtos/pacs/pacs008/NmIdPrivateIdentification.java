package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class NmIdPrivateIdentification {

    @JsonPropertyCustom(value = "Nm", required = true)
    protected String name;

    @JsonPropertyCustom(value = "Id", required = true)
    protected PrivateIdentification id;
}
