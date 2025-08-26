package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ClearingSystemMemberIdentification {

    @JsonProperty(value = "MmbId", required = true)
    protected String mmbId;
}
