package br.kauan.spi.domain.entity.commons;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BatchDetails {
    private String id;
    private Instant createdAt;
    private Integer totalTransactions;
}