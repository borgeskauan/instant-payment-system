package br.kauan.paymentserviceprovider.adapter.input.notification;

import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.domain.services.cts.IncomingTransactionService;
import br.kauan.paymentserviceprovider.domain.services.cts.PaymentOutcomeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationProcessor {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final PaymentOutcomeService paymentOutcomeService;
    private final IncomingTransactionService incomingTransactionService;
    private final ObjectMapper objectMapper;

    public NotificationProcessor(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentOutcomeService paymentOutcomeService,
            IncomingTransactionService incomingTransactionService,
            ObjectMapper objectMapper
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.paymentOutcomeService = paymentOutcomeService;
        this.incomingTransactionService = incomingTransactionService;
        this.objectMapper = objectMapper;
    }

    public void process(String notificationJson) {
        try {
            JsonNode jsonNode = objectMapper.readTree(notificationJson);

            if (jsonNode.has("TxInfAndSts")) {
                processStatusReport(jsonNode);
                return;
            }

            if (jsonNode.has("CdtTrfTxInf")) {
                processPaymentTransaction(jsonNode);
                return;
            }

            throw new IllegalArgumentException("Unknown notification type");
        } catch (Exception e) {
            log.error("Error processing notification", e);
            throw new NotificationProcessingException("Failed to process notification", e);
        }
    }

    private void processPaymentTransaction(JsonNode notification) throws Exception {
        FIToFICustomerCreditTransfer creditTransfer = objectMapper.treeToValue(
                notification,
                FIToFICustomerCreditTransfer.class);

        var transactions = paymentTransactionMapper.fromRegulatoryRequest(creditTransfer);

        log.info("Processing incoming payment notification with {} transactions", transactions.size());

        incomingTransactionService.handleTransferRequests(transactions);
    }

    private void processStatusReport(JsonNode notification) throws Exception {
        FIToFIPaymentStatusReport statusReport = objectMapper.treeToValue(
                notification,
                FIToFIPaymentStatusReport.class);

        var statusReports = statusReportMapper.fromRegulatoryReport(statusReport);

        log.info("Processing status notification with {} reports", statusReports.size());

        paymentOutcomeService.handleStatuses(statusReports);
    }
}
