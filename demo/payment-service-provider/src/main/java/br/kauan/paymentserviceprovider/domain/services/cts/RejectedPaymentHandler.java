package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class RejectedPaymentHandler {

    public void handleRejectedPayments(List<StatusReport> statusReports) {
        // TODO: Implement rejected payment handling (notifications, etc.)
        log.debug("No specific action required for {} rejected payments", statusReports.size());
    }
}
