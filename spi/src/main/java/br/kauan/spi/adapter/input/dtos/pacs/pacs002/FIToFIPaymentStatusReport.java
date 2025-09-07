package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FIToFIPaymentStatusReport {

    @JsonPropertyCustom(value = "GrpHdr", required = true)
    protected GroupHeader groupHeader;

    @JsonPropertyCustom(value = "TxInfAndSts", required = true)
    protected List<PaymentTransactionInfo> transactionInfo;
}
