package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class PersonIdentification {

    @JsonPropertyCustom(value = "Othr", required = true)
    protected GenericPersonIdentification other;
}
