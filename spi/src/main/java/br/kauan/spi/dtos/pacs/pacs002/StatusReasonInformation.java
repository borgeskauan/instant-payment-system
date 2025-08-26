package br.kauan.spi.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class StatusReasonInformation {

    @JsonProperty(value = "Rsn")
    protected StatusReason reason;

    @JsonProperty(value = "AddtlInf")
    protected List<String> additionalInformation;
}
