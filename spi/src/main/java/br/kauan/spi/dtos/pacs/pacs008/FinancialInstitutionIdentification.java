package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class FinancialInstitutionIdentification {

    @JsonPropertyCustom(value = "FinInstnId", required = true)
    protected FinancialInstitutionIdentificationInternal financialInstitutionIdentification;
}
