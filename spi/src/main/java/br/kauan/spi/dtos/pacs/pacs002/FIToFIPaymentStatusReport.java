package br.kauan.spi.dtos.pacs.pacs002;

import br.kauan.spi.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FIToFIPaymentStatusReport {

    @JsonPropertyCustom(value = "GrpHdr", required = true)
    protected GroupHeaderStatusReport groupHeader;

    @JsonPropertyCustom(value = "TxInfAndSts", required = true)
    protected List<PaymentTransactionInfo> transactionInfo;
}
