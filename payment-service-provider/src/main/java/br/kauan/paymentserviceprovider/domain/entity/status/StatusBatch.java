package br.kauan.paymentserviceprovider.domain.entity.status;

import br.kauan.paymentserviceprovider.domain.entity.BatchDetails;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StatusBatch {
    private BatchDetails batchDetails;
    private List<StatusReport> statusReports;
}