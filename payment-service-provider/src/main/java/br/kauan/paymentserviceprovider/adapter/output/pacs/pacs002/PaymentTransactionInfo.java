package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransactionInfo {

    @JsonProperty(value = "OrgnlEndToEndId", required = true)
    protected String originalPaymentId;

    @JsonProperty(value = "TxSts", required = true)
    protected ExternalPaymentTransactionStatusCode status;

    @JsonProperty("StsRsnInf")
    protected List<StatusReasonInformation> statusReasonInformations;
}
