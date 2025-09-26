package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
public class StatusProcessingService {
    
    private final PaymentRepository paymentRepository;
    private final SettlementService settlementService;
    private final RejectedPaymentHandler rejectedPaymentHandler;

    public StatusProcessingService(
            PaymentRepository paymentRepository,
            SettlementService settlementService,
            RejectedPaymentHandler rejectedPaymentHandler) {
        this.paymentRepository = paymentRepository;
        this.settlementService = settlementService;
        this.rejectedPaymentHandler = rejectedPaymentHandler;
    }

    public void handleStatusBatch(StatusBatch statusBatch) {
        log.info("Received status batch with {} reports", statusBatch.getStatusReports().size());
        statusBatch.getStatusReports().forEach(this::processStatusReport);
    }

    private void processStatusReport(StatusReport statusReport) {
        String paymentId = statusReport.getOriginalPaymentId();
        log.info("Processing status report for payment: {}", paymentId);

        if (PaymentStatus.REJECTED.equals(statusReport.getStatus())) {
            log.warn("Payment {} was rejected", paymentId);
            rejectedPaymentHandler.handleRejectedPayment(statusReport);
            return;
        }

        PaymentTransaction payment = findPaymentById(paymentId);
        settlementService.handleSettlement(statusReport.getStatus(), payment);
    }

    private PaymentTransaction findPaymentById(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
    }
}