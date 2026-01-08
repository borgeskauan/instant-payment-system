package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.datatype.XMLGregorianCalendar;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupHeaderStatusReport {

    @JsonProperty(value = "MsgId", required = true)
    protected String messageId;

    @JsonProperty(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creationTimestamp;
}
