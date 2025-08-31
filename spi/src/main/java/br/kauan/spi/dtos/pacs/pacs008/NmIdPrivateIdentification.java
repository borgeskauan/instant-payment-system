package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NmIdPrivateIdentification {

    @JsonPropertyCustom(value = "Nm", required = true)
    protected String name;

    @JsonPropertyCustom(value = "Id", required = true)
    protected PrivateIdentification id;
}
