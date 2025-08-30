package br.kauan.spi.domain.entity.status;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StatusReport { // rename to StatusBatch
    private ReportDetails reportDetails; // rename to BatchDetails
    private List<StatusUpdate> statusUpdates; // rename to StatusReport
}