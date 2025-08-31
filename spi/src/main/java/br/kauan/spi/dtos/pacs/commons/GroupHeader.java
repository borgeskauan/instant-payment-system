package br.kauan.spi.dtos.pacs.commons;

import br.kauan.spi.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;

@Data
@Builder
public class GroupHeader {

    @JsonPropertyCustom(value = "MsgId", required = true)
    protected String messageId;

    @JsonPropertyCustom(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creationTimestamp;

    @JsonPropertyCustom(value = "NbOfTxs", required = true)
    protected BigInteger numberOfTransactions;
}
