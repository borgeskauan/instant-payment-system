package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002;

import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.GroupHeader;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FIToFIPaymentStatusReport {

    @JsonProperty(value = "GrpHdr", required = true)
    protected GroupHeader groupHeader;

    @JsonProperty(value = "TxInfAndSts", required = true)
    protected List<PaymentTransactionInfo> transactionInfo;
}
