package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.GroupHeader;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FIToFICustomerCreditTransfer {

    @JsonProperty(value = "GrpHdr", required = true)
    protected GroupHeader groupHeader;

    @JsonProperty(value = "CdtTrfTxInf", required = true)
    protected List<CreditTransferTransaction> creditTransferTransactions;
}
