package br.kauan.spi.dtos.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class PaymentTransaction {

    @JsonProperty(value = "OrgnlInstrId", required = true)
    protected String originalMessageId;

    @JsonProperty(value = "OrgnlEndToEndId", required = true)
    protected String originalPaymentId;

    @JsonProperty(value = "TxSts", required = true)
    protected ExternalPaymentTransactionStatusCode status;

    @JsonProperty("StsRsnInf")
    protected List<StatusReasonInformation> statusReasonInformations;
}
