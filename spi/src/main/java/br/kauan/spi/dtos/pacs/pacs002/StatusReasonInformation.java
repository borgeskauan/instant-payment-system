package br.kauan.spi.dtos.pacs.pacs002;

import br.kauan.spi.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StatusReasonInformation {

    @JsonPropertyCustom(value = "Rsn")
    protected StatusReason reason;

    @JsonPropertyCustom(value = "AddtlInf")
    protected List<String> additionalInformation;
}
