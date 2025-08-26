package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PrivateIdentification {

    @JsonProperty(value = "PrvtId", required = true)
    protected PersonIdentification personIdentification;
}
