package br.kauan.spi.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class StatusReason {

    @JsonProperty(value = "Cd")
    protected ExternalStatusReasonCode code;
}
