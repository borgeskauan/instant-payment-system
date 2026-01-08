package br.kauan.spi.adapter.input.dtos.pacs.pacs002;

import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
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
public class FIToFIPaymentStatusReport {

    @JsonProperty(value = "GrpHdr", required = true)
    protected GroupHeader groupHeader;

    @JsonProperty(value = "TxInfAndSts", required = true)
    protected List<PaymentTransactionInfo> transactionInfo;
}
