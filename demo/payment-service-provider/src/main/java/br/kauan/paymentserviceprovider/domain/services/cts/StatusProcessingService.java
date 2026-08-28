package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public void handleStatuses(List<StatusReport> statusReports) {
        log.info("[PIX FLOW - Step 8] PSP Pagador received {} status reports from SPI",
                statusReports.size());
        processStatusReports(statusReports);
    }

    private void processStatusReports(List<StatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return;
        }

        List<StatusReport> rejectedReports = new ArrayList<>();
        List<StatusReport> settlementReports = new ArrayList<>();
        List<String> settlementPaymentIds = new ArrayList<>();

        for (StatusReport statusReport : statusReports) {
            if (PaymentStatus.REJECTED.equals(statusReport.getStatus())) {
                rejectedReports.add(statusReport);
                continue;
            }

            settlementReports.add(statusReport);
            settlementPaymentIds.add(statusReport.getOriginalPaymentId());
        }

        if (!rejectedReports.isEmpty()) {
            log.warn("[PIX FLOW - Step 8] PSP Pagador processing {} rejected payments", rejectedReports.size());
            rejectedPaymentHandler.handleRejectedPayments(rejectedReports);
        }

        if (settlementReports.isEmpty()) {
            return;
        }

        Map<String, PaymentTransaction> paymentsById = findPaymentsById(settlementPaymentIds);
        log.info("[PIX FLOW - Step 8/9] PSP Pagador processing settlement batch with {} status reports",
                settlementReports.size());
        settlementService.handleSettlements(settlementReports, paymentsById);
    }

    private Map<String, PaymentTransaction> findPaymentsById(List<String> paymentIds) {
        List<PaymentTransaction> payments = paymentRepository.findAllByIds(paymentIds);
        Map<String, PaymentTransaction> paymentsById = new HashMap<>(payments.size());
        for (PaymentTransaction payment : payments) {
            paymentsById.put(payment.getPaymentId(), payment);
        }
        return paymentsById;
    }
}
