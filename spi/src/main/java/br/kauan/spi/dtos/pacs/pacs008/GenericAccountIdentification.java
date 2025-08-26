package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigInteger;

@Data
public class GenericAccountIdentification {

    @JsonProperty(value = "Id", required = true)
    protected BigInteger id;

    @JsonProperty(value = "Issr")
    protected BigInteger issr;
}
