package br.kauan.spi.domain.entity.status;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ReportDetails {
    private String reportId;
    private Instant generatedAt;
}
