package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;

@Data
public class GroupHeaderCreditTransfer {

    @JsonPropertyCustom(value = "MsgId", required = true)
    protected String messageId;

    @JsonPropertyCustom(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creationTimestamp;

    @JsonPropertyCustom(value = "NbOfTxs", required = true)
    protected BigInteger numberOfTransactions;
}
