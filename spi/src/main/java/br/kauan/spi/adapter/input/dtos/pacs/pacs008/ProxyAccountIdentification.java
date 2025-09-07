package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProxyAccountIdentification {

    @JsonPropertyCustom(value = "Id", required = true)
    protected String pixKey;
}
