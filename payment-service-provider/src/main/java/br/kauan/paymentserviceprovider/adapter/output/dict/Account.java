package br.kauan.paymentserviceprovider.adapter.output.dict;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class Account {
    private String participant;
    private String branch;
    private String number;
    private String type;
    private Instant openingDate;
}
