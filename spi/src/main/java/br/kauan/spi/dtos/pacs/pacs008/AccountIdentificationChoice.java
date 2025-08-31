package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccountIdentificationChoice {

    @JsonPropertyCustom(value = "Othr")
    protected GenericAccountIdentification other;
}
