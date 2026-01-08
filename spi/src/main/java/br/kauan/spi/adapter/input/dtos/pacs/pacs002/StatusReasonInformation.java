package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusReasonInformation {

    @JsonProperty(value = "Rsn")
    protected StatusReason reason;

    @JsonProperty(value = "AddtlInf")
    protected List<String> additionalInformation;
}
