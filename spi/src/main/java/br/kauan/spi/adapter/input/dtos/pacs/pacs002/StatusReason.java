package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusReason {

    @JsonProperty(value = "Cd")
    protected ExternalStatusReasonCode code;
}
