package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class AccountIdentificationChoice {

    @JsonPropertyCustom(value = "Othr")
    protected GenericAccountIdentification other;
}
