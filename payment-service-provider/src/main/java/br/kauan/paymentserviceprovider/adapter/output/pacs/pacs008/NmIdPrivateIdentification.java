package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NmIdPrivateIdentification {

    @JsonProperty(value = "Nm", required = true)
    protected String name;

    @JsonProperty(value = "Id", required = true)
    protected PrivateIdentification id;
}
