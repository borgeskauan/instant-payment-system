package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PersonIdentification {

    @JsonProperty(value = "Othr", required = true)
    protected GenericPersonIdentification other;
}
