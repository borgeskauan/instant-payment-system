package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClearingSystemMemberIdentification {

    @JsonPropertyCustom(value = "MmbId", required = true)
    protected String ispb;
}
