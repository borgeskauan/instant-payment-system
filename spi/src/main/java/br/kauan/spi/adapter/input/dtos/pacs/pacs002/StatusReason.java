package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import br.kauan.spi.adapter.input.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StatusReason {

    @JsonPropertyCustom(value = "Cd")
    protected ExternalStatusReasonCode code;
}
