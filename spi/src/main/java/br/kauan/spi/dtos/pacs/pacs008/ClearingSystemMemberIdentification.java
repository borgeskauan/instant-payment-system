package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class ClearingSystemMemberIdentification {

    @JsonPropertyCustom(value = "MmbId", required = true)
    protected String ispb;
}
