package br.kauan.spi.domain.entity.transfer;

import lombok.Data;

import java.time.Instant;

@Data
public class BatchDetails {
    private String id;
    private Instant createdAt;
    private Integer totalTransactions;
}