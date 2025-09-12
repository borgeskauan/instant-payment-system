package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentTransactionInfo {

    @JsonProperty(value = "OrgnlEndToEndId", required = true)
    protected String originalPaymentId;

    @JsonProperty(value = "TxSts", required = true)
    protected ExternalPaymentTransactionStatusCode status;

    @JsonProperty("StsRsnInf")
    protected List<StatusReasonInformation> statusReasonInformations;
}
