package br.kauan.spi.dtos.pacs.pacs002;

import br.kauan.spi.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

import javax.xml.datatype.XMLGregorianCalendar;

@Data
@Builder
public class GroupHeaderStatusReport {

    @JsonPropertyCustom(value = "MsgId", required = true)
    protected String messageId;

    @JsonPropertyCustom(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creationTimestamp;
}
