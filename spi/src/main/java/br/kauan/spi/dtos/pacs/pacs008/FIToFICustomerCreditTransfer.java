package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

import java.util.List;

@Data
public class FIToFICustomerCreditTransfer {

    @JsonPropertyCustom(value = "GrpHdr", required = true)
    protected GroupHeaderCreditTransfer groupHeader;

    @JsonPropertyCustom(value = "CdtTrfTxInf", required = true)
    protected List<CreditTransferTransaction> creditTransferTransactions;
}
