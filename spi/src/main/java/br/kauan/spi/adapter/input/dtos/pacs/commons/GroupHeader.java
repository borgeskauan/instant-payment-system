package br.kauan.spi.adapter.input.dtos.pacs.commons;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupHeader {

    @JsonProperty(value = "MsgId", required = true)
    protected String messageId;

    @JsonProperty(value = "CreDtTm", required = true)
    protected OffsetDateTime creationTimestamp;

    @JsonProperty(value = "NbOfTxs", required = true)
    protected BigInteger numberOfTransactions;
}
