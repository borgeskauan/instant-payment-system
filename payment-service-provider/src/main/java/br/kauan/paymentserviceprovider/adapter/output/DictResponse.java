package br.kauan.paymentserviceprovider.adapter.output;

import lombok.Data;

import java.time.Instant;

@Data
public class DictResponse {

    private String key;
    private String keyType;
    private Account account;
    private Owner owner;
    private Instant creationDate;
    private Instant keyOwnershipDate;
}
