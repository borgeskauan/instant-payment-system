package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RejectedPaymentHandler {
    
    public void handleRejectedPayment(StatusReport statusReport) {
        // TODO: Implement rejected payment handling (notifications, etc.)
        log.debug("No specific action required for rejected payment: {}", statusReport.getOriginalPaymentId());
    }
}