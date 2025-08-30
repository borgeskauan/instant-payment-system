package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class ProxyAccountIdentification {

    @JsonPropertyCustom(value = "Id", required = true)
    protected String pixKey;
}
