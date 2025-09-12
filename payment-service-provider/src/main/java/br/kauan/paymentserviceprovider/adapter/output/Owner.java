package br.kauan.paymentserviceprovider.adapter.output;

import lombok.Data;

@Data
public class Owner {
    private String type;
    private String taxIdNumber;
    private String name;
}
