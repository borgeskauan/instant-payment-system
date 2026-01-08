package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClearingSystemMemberIdentification {

    @JsonProperty(value = "MmbId", required = true)
    protected String ispb;
}
