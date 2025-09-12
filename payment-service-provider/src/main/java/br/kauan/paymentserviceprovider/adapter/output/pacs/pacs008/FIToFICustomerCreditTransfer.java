package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.CreditTransferTransaction;
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
