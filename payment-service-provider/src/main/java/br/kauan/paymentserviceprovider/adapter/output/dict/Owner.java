package br.kauan.paymentserviceprovider.adapter.output.dict;

import lombok.Data;

@Data
public class Owner {
    private String type;
    private String taxIdNumber;
    private String name;
}
