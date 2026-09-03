package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashAccount {

    @JsonProperty(value = "Id", required = true)
    protected AccountIdentificationChoice id;

    @JsonProperty(value = "Tp", required = true)
    protected CashAccountTypeChoice accountType;

    @JsonProperty(value = "Prxy")
    protected ProxyAccountIdentification proxyAccountIdentification;
}
