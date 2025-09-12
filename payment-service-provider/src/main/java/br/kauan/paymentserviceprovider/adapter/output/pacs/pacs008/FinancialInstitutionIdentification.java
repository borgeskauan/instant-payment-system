package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FinancialInstitutionIdentification {

    @JsonProperty(value = "FinInstnId", required = true)
    protected FinancialInstitutionIdentificationInternal financialInstitutionIdentification;
}
