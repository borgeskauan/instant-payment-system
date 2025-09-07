package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

import java.math.BigInteger;

@Data
@Builder
public class GenericAccountIdentification {

    @JsonPropertyCustom(value = "Id", required = true)
    protected BigInteger id;

    @JsonPropertyCustom(value = "Issr")
    protected BigInteger branchCode;
}
