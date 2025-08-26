package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ProxyAccountIdentification {

    @JsonProperty(value = "Id", required = true)
    protected String id;
}
