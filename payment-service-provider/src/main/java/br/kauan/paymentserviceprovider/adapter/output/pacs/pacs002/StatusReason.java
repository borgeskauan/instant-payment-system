package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StatusReason {

    @JsonProperty(value = "Cd")
    protected ExternalStatusReasonCode code;
}
