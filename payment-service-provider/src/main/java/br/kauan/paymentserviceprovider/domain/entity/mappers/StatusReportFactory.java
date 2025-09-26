package br.kauan.paymentserviceprovider.domain.entity.mappers;

import br.kauan.paymentserviceprovider.domain.entity.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StatusReportFactory {

    public StatusBatch createStatusBatch(StatusReport statusReport) {
        return StatusBatch.builder()
                .batchDetails(BatchDetails.of(1))
                .statusReports(List.of(statusReport))
                .build();
    }
}
