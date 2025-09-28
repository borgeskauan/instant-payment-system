package br.kauan.paymentserviceprovider.domain.entity.commons;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class BatchDetails {
    private String id;
    private Instant createdAt;
    private Integer totalTransactions;

    public static BatchDetails of(Integer totalTransactions) {
        return BatchDetails.builder()
                .id(UUID.randomUUID().toString())
                .createdAt(Instant.now())
                .totalTransactions(totalTransactions)
                .build();
    }
}