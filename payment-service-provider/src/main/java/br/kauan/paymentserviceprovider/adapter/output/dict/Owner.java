package br.kauan.paymentserviceprovider.adapter.output.dict;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Owner {
    private String type;
    private String taxIdNumber;
    private String name;
}
