package br.kauan.spi.dtos.pacs.pacs008;

import br.kauan.spi.dtos.pacs.commons.GroupHeader;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FIToFICustomerCreditTransfer {

    @JsonPropertyCustom(value = "GrpHdr", required = true)
    protected GroupHeader groupHeader;

    @JsonPropertyCustom(value = "CdtTrfTxInf", required = true)
    protected List<CreditTransferTransaction> creditTransferTransactions;
}
