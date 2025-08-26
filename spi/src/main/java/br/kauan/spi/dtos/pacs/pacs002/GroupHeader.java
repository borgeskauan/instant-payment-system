package br.kauan.spi.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.xml.datatype.XMLGregorianCalendar;

@Data
public class GroupHeader {

    @JsonProperty(value = "MsgId", required = true)
    protected String messageId;

    @JsonProperty(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creationTimestamp;
}
