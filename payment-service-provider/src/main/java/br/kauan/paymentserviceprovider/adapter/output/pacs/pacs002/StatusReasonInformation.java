package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StatusReasonInformation {

    @JsonProperty(value = "Rsn")
    protected StatusReason reason;

    @JsonProperty(value = "AddtlInf")
    protected List<String> additionalInformation;
}
