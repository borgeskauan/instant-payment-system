package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenericAccountIdentification {

    @JsonProperty(value = "Id", required = true)
    protected BigInteger id;

    @JsonProperty(value = "Issr")
    protected BigInteger branchCode;
}
