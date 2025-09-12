package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FinancialInstitutionIdentificationInternal;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FinancialInstitutionIdentification {

    @JsonProperty(value = "FinInstnId", required = true)
    protected FinancialInstitutionIdentificationInternal financialInstitutionIdentification;
}
