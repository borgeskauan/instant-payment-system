package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PersonIdentification {

    @JsonPropertyCustom(value = "Othr", required = true)
    protected GenericPersonIdentification other;
}
