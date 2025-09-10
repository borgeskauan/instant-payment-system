package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StatusReason {

    @JsonProperty(value = "Cd")
    protected ExternalStatusReasonCode code;
}
