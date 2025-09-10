package br.kauan.spi.domain.entity.status;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StatusBatch {
    private BatchDetails batchDetails;
    private List<StatusReport> statusReports;
}