package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class FinancialInstitutionIdentificationInternal {

    @JsonPropertyCustom(value = "ClrSysMmbId", required = true)
    protected ClearingSystemMemberIdentification clearingSystemMemberIdentification;
}
