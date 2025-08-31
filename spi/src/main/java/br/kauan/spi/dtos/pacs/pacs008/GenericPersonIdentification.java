package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GenericPersonIdentification {

    @JsonPropertyCustom(value = "Id", required = true)
    protected String cpfCnpj;
}
