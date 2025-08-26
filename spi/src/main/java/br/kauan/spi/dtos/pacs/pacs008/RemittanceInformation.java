package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RemittanceInformation {

    @JsonProperty(value = "Ustrd")
    protected String additionalInformation;
}
