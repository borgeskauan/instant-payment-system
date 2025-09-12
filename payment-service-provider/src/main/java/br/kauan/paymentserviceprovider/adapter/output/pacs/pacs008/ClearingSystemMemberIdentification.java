package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClearingSystemMemberIdentification {

    @JsonProperty(value = "MmbId", required = true)
    protected String ispb;
}
