package br.kauan.spi.dtos.pacs.pacs002;

import br.kauan.spi.dtos.pacs.pacs008.JsonPropertyCustom;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentTransactionInfo {

    @JsonPropertyCustom(value = "OrgnlInstrId", required = true)
    protected String originalMessageId;

    @JsonPropertyCustom(value = "OrgnlEndToEndId", required = true)
    protected String originalPaymentId;

    @JsonPropertyCustom(value = "TxSts", required = true)
    protected ExternalPaymentTransactionStatusCode status;

    @JsonPropertyCustom("StsRsnInf")
    protected List<StatusReasonInformation> statusReasonInformations;
}
