package br.kauan.paymentserviceprovider.adapter.output;

import lombok.Data;

import java.time.Instant;

@Data
public class Account {
    private String participant;
    private String branch;
    private String number;
    private String type;
    private Instant openingDate;
}
