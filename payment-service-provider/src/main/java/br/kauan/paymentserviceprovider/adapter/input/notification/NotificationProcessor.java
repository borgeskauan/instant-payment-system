package br.kauan.paymentserviceprovider.adapter.input.notification;

import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.services.cts.IncomingTransactionService;
import br.kauan.paymentserviceprovider.domain.services.cts.StatusProcessingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationProcessor {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final StatusProcessingService statusProcessingService;
    private final IncomingTransactionService incomingTransactionService;
    private final ObjectMapper objectMapper;

    public NotificationProcessor(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            StatusProcessingService statusProcessingService,
            IncomingTransactionService incomingTransactionService,
            ObjectMapper objectMapper
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.statusProcessingService = statusProcessingService;
        this.incomingTransactionService = incomingTransactionService;
        this.objectMapper = objectMapper;
    }

    public void process(String ispb, String notificationJson) {
        try {
            String currentBankCode = GlobalVariables.getBankCode();
            if (!currentBankCode.equals(ispb)) {
                log.trace("Ignoring notification for ISPB: {} (current bank: {})", ispb, currentBankCode);
                return;
            }

            JsonNode jsonNode = objectMapper.readTree(notificationJson);

            if (jsonNode.has("TxInfAndSts")) {
                processStatusReport(notificationJson);
                return;
            }

            if (jsonNode.has("CdtTrfTxInf")) {
                processPaymentTransaction(notificationJson);
                return;
            }

            throw new IllegalArgumentException("Unknown notification type received for ISPB: " + ispb);
        } catch (Exception e) {
            log.error("Error processing notification for ISPB: {}", ispb, e);
            throw new NotificationProcessingException("Failed to process notification", e);
        }
    }

    private void processPaymentTransaction(String notificationJson) throws Exception {
        FIToFICustomerCreditTransfer creditTransfer = objectMapper.readValue(
                notificationJson,
                FIToFICustomerCreditTransfer.class
        );

        var transactions = paymentTransactionMapper.fromRegulatoryRequest(creditTransfer);

        log.info("Processing incoming payment notification with {} transactions", transactions.size());

        incomingTransactionService.handleTransferRequests(transactions);
    }

    private void processStatusReport(String notificationJson) throws Exception {
        FIToFIPaymentStatusReport statusReport = objectMapper.readValue(
                notificationJson,
                FIToFIPaymentStatusReport.class
        );

        var statusReports = statusReportMapper.fromRegulatoryReport(statusReport);

        log.info("Processing status notification with {} reports", statusReports.size());

        statusProcessingService.handleStatuses(statusReports);
    }
}
