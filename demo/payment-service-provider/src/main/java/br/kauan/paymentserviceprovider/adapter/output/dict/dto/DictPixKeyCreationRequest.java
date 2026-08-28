package br.kauan.paymentserviceprovider.adapter.output.dict.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DictPixKeyCreationRequest {
    private String key;
    private String keyType;
    private Account account;
    private Owner owner;
}
