package br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigInteger;

@Data
@Builder
public class GenericAccountIdentification {

    @JsonProperty(value = "Id", required = true)
    protected BigInteger id;

    @JsonProperty(value = "Issr")
    protected BigInteger branchCode;
}
