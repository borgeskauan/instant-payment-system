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
        log.info("[PIX FLOW - Step 8] PSP Pagador received status batch with {} reports from SPI", 
                statusBatch.getStatusReports().size());
        statusBatch.getStatusReports().forEach(this::processStatusReport);
    }

    private void processStatusReport(StatusReport statusReport) {
        String paymentId = statusReport.getOriginalPaymentId();
        log.info("[PIX FLOW - Step 8] PSP Pagador processing status report for payment: {}, Status: {}", 
                paymentId, statusReport.getStatus());

        if (PaymentStatus.REJECTED.equals(statusReport.getStatus())) {
            log.warn("[PIX FLOW - Step 8] Payment {} was rejected", paymentId);
            rejectedPaymentHandler.handleRejectedPayment(statusReport);
            return;
        }

        PaymentTransaction payment = findPaymentById(paymentId);
        log.info("[PIX FLOW - Step 8/9] PSP Pagador processing settlement based on status: {}", 
                statusReport.getStatus());
        settlementService.handleSettlement(statusReport.getStatus(), payment);
    }

    private PaymentTransaction findPaymentById(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
    }
}