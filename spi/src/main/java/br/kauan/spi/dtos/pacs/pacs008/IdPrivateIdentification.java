package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class IdPrivateIdentification {

    @JsonProperty(value = "Id", required = true)
    protected PrivateIdentification id;
}
