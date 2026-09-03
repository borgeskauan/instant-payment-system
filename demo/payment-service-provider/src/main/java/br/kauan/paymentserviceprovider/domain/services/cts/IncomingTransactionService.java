package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.state.IncomingPaymentClassification;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class IncomingTransactionService {
    
    private final PaymentStore paymentStore;
    private final StatusReportMapper statusReportMapper;
    private final CentralTransferSystemRestClient transferRestClient;
    private final ObjectMapper objectMapper;

    public IncomingTransactionService(
            PaymentStore paymentStore,
            StatusReportMapper statusReportMapper,
            CentralTransferSystemRestClient transferRestClient,
            ObjectMapper objectMapper
    ) {
        this.paymentStore = paymentStore;
        this.statusReportMapper = statusReportMapper;
        this.transferRestClient = transferRestClient;
        this.objectMapper = objectMapper;
    }

    public void handleTransferRequests(List<PaymentTransaction> transactions) {
        if (transactions.isEmpty()) {
            return;
        }

        log.info("[PIX FLOW - Step 4] PSP Recebedor received {} incoming transactions from SPI",
                transactions.size());
        List<StatusReport> statusReports = classifyAcceptedPayments(transactions);
        if (statusReports.isEmpty()) {
            log.info("[PIX FLOW - Step 5] No accepted incoming transactions to report to SPI");
            return;
        }

        byte[] statusBytes;
        try {
            var regulatoryStatusReport = statusReportMapper.toRegulatoryReport(statusReports);
            statusBytes = objectMapper.writeValueAsBytes(regulatoryStatusReport);
        } catch (JsonProcessingException e) {
            log.error("[PIX FLOW - Error] Failed to serialize status reports", e);
            throw new IllegalStateException("Failed to serialize status reports", e);
        }

        log.info("[PIX FLOW - Step 5] PSP Recebedor sending {} acceptances (PACS.002) to SPI",
                statusReports.size());
        transferRestClient.sendTransferStatus(statusBytes);
        log.info("[PIX FLOW - Step 5] Acceptances sent successfully to kafka-producer (will be forwarded to SPI)");
    }

    private List<StatusReport> classifyAcceptedPayments(List<PaymentTransaction> transactions) {
        IncomingPaymentClassification classification = paymentStore.storeAndClassifyIncoming(transactions);
        if (!classification.divergentPayments().isEmpty()) {
            log.warn("[PIX FLOW - Step 4] PSP Recebedor detected {} divergent incoming transactions",
                    classification.divergentPayments().size());
        }

        log.info("[PIX FLOW - Step 4] PSP Recebedor classified {} incoming transactions for acceptance. Auto-approving payments.",
                classification.acceptedPayments().size());

        List<StatusReport> statusReports = new ArrayList<>(classification.acceptedPayments().size());
        for (PaymentTransaction transaction : classification.acceptedPayments()) {
            statusReports.add(buildApprovedStatusReport(transaction.getPaymentId()));
        }
        return statusReports;
    }

    private StatusReport buildApprovedStatusReport(String paymentId) {
        return StatusReport.builder()
                .originalPaymentId(paymentId)
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
    }
}
