package br.kauan.paymentserviceprovider.adapter.input.kafka;

import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.services.cts.IncomingTransactionService;
import br.kauan.paymentserviceprovider.domain.services.cts.StatusProcessingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationConsumer {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final StatusProcessingService statusProcessingService;
    private final IncomingTransactionService incomingTransactionService;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            StatusProcessingService statusProcessingService,
            IncomingTransactionService incomingTransactionService
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.statusProcessingService = statusProcessingService;
        this.incomingTransactionService = incomingTransactionService;
        this.objectMapper = new ObjectMapper();
    }

    @KafkaListener(
            topics = "notifications-topic",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consumeNotification(
            @Payload String notificationJson,
            @Header(KafkaHeaders.RECEIVED_KEY) String ispb,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        try {
            // Only process notifications for this bank
            String currentBankCode = GlobalVariables.getBankCode();
            if (!currentBankCode.equals(ispb)) {
                log.trace("Ignoring notification for ISPB: {} (current bank: {})", ispb, currentBankCode);
                return;
            }

            log.debug("Received notification for ISPB: {} from partition: {}, offset: {}", 
                    ispb, partition, offset);
            
            // Determine message type and process
            JsonNode jsonNode = objectMapper.readTree(notificationJson);
            
            if (jsonNode.has("TxInfAndSts")) {
                // This is a status report (pacs.002)
                processStatusReport(notificationJson);
            } else if (jsonNode.has("CdtTrfTxInf")) {
                // This is a payment transaction (pacs.008)
                processPaymentTransaction(notificationJson);
            } else {
                log.warn("Unknown notification type received for ISPB: {}", ispb);
            }
            
        } catch (Exception e) {
            log.error("Error processing notification from Kafka for ISPB: {}", ispb, e);
            // Consider implementing dead letter queue or retry logic here
        }
    }

    private void processPaymentTransaction(String notificationJson) {
        try {
            FIToFICustomerCreditTransfer creditTransfer = objectMapper.readValue(
                    notificationJson, 
                    FIToFICustomerCreditTransfer.class
            );
            
            PaymentBatch paymentBatch = paymentTransactionMapper.fromRegulatoryRequest(creditTransfer);
            
            log.info("Processing incoming payment batch with {} transactions", 
                    paymentBatch.getTransactions().size());
            
            incomingTransactionService.handleTransferRequestBatch(paymentBatch);
            
        } catch (Exception e) {
            log.error("Error processing payment transaction notification from Kafka", e);
            throw new RuntimeException("Failed to process payment transaction notification", e);
        }
    }

    private void processStatusReport(String notificationJson) {
        try {
            FIToFIPaymentStatusReport statusReport = objectMapper.readValue(
                    notificationJson, 
                    FIToFIPaymentStatusReport.class
            );
            
            StatusBatch statusBatch = statusReportMapper.fromRegulatoryReport(statusReport);
            
            log.info("Processing status batch with {} reports", 
                    statusBatch.getStatusReports().size());
            
            statusProcessingService.handleStatusBatch(statusBatch);
            
        } catch (Exception e) {
            log.error("Error processing status report notification from Kafka", e);
            throw new RuntimeException("Failed to process status report notification", e);
        }
    }
}
