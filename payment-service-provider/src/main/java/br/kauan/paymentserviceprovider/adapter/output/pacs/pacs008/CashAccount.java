package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import br.kauan.spi.adapter.input.dtos.pacs.pacs008.AccountIdentificationChoice;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.CashAccountTypeChoice;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.ProxyAccountIdentification;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CashAccount {

    @JsonProperty(value = "Id", required = true)
    protected AccountIdentificationChoice id;

    @JsonProperty(value = "Tp", required = true)
    protected CashAccountTypeChoice accountType;

    @JsonProperty(value = "Prxy")
    protected ProxyAccountIdentification proxyAccountIdentification;
}
