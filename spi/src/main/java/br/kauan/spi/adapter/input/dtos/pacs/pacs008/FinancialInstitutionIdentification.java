package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FinancialInstitutionIdentification {

    @JsonPropertyCustom(value = "FinInstnId", required = true)
    protected FinancialInstitutionIdentificationInternal financialInstitutionIdentification;
}
