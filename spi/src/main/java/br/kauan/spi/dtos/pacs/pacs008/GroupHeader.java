package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;

@Data
public class GroupHeader {

    @JsonProperty(value = "MsgId", required = true)
    protected String msgId;

    @JsonProperty(value = "CreDtTm", required = true)
    protected XMLGregorianCalendar creDtTm;

    @JsonProperty(value = "NbOfTxs", required = true)
    protected BigInteger nbOfTxs;
}
