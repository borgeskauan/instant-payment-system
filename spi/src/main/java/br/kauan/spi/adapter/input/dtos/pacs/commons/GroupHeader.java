package br.kauan.spi.adapter.input.dtos.pacs.commons;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;

@Data
@Builder
public class GroupHeader {

    @JsonProperty(value = "MsgId", required = true)
    protected String messageId;

    @JsonProperty(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creationTimestamp;

    @JsonProperty(value = "NbOfTxs", required = true)
    protected BigInteger numberOfTransactions;
}
