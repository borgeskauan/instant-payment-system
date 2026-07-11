package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.IncomingPaymentRequestClassification;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@Transactional
public class IncomingTransactionService {
    
    private final PaymentRepository paymentRepository;
    private final StatusReportMapper statusReportMapper;
    private final CentralTransferSystemRestClient transferRestClient;
    private final ObjectMapper objectMapper;

    public IncomingTransactionService(
            PaymentRepository paymentRepository,
            StatusReportMapper statusReportMapper,
            CentralTransferSystemRestClient transferRestClient,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.statusReportMapper = statusReportMapper;
        this.transferRestClient = transferRestClient;
        this.objectMapper = objectMapper;
    }

    public void handleTransferRequests(List<PaymentTransaction> transactions) {
        log.info("[PIX FLOW - Step 4] PSP Recebedor received {} incoming transactions from SPI",
                transactions.size());
        processTransferRequests(transactions);
    }

    private void processTransferRequests(List<PaymentTransaction> transactions) {
        if (transactions.isEmpty()) {
            return;
        }

        try {
            List<StatusReport> statusReports = handleIncomingTransactions(transactions);
            if (statusReports.isEmpty()) {
                log.info("[PIX FLOW - Step 5] No accepted incoming transactions to report to SPI");
                return;
            }

            var regulatoryStatusReport = statusReportMapper.toRegulatoryReport(statusReports);
            byte[] statusBytes = objectMapper.writeValueAsBytes(regulatoryStatusReport);

            log.info("[PIX FLOW - Step 5] PSP Recebedor sending {} acceptances (PACS.002) to SPI",
                    statusReports.size());
            transferRestClient.sendTransferStatus(GlobalVariables.getBankCode(), statusBytes);
            log.info("[PIX FLOW - Step 5] Acceptances sent successfully to kafka-producer (will be forwarded to SPI)");
        } catch (Exception e) {
            log.error("[PIX FLOW - Error] Failed to serialize status reports", e);
            throw new RuntimeException("Failed to send status reports", e);
        }

        log.debug("[PIX FLOW - Step 5] Sent status reports for {} transactions", transactions.size());
    }

    private List<StatusReport> handleIncomingTransactions(List<PaymentTransaction> transactions) {
        IncomingPaymentRequestClassification classification =
                paymentRepository.storeAndClassifyIncomingRequests(transactions);
        if (!classification.divergentPaymentRequests().isEmpty()) {
            log.warn("[PIX FLOW - Step 4] PSP Recebedor detected {} divergent incoming transactions",
                    classification.divergentPaymentRequests().size());
        }

        log.info("[PIX FLOW - Step 4] PSP Recebedor classified {} incoming transactions for acceptance. Auto-approving payments.",
                classification.acceptedPaymentRequests().size());

        List<StatusReport> statusReports = new ArrayList<>(classification.acceptedPaymentRequests().size());
        for (PaymentTransaction transaction : classification.acceptedPaymentRequests()) {
            // TODO: Implement proper business logic for transaction approval
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
