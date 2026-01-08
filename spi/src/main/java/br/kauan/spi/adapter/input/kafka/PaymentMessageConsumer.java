package br.kauan.spi.adapter.input.kafka;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentMessageConsumer {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final ObjectMapper objectMapper;

    public PaymentMessageConsumer(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.objectMapper = new ObjectMapper();
        log.info("PaymentMessageConsumer initialized - ready to consume from topic 'high-load-binary-topic'");
    }

    @KafkaListener(
            topics = "high-load-binary-topic",
            groupId = "spi-consumer-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeMessage(byte[] payload) {
        try {
            log.info("Received message from Kafka topic 'high-load-binary-topic', size: {} bytes", payload.length);
            
            // Deserialize the payload to determine message type
            String jsonString = new String(payload);
            var jsonNode = objectMapper.readTree(jsonString);
            
            // Determine message type and process accordingly
            if (jsonNode.has("TxInfAndSts")) {
                // This is a status report (pacs.002)
                log.info("Detected status report (pacs.002) message");
                processStatusReport(jsonString);
            } else if (jsonNode.has("CdtTrfTxInf")) {
                // This is a payment transaction (pacs.008)
                log.info("Detected payment transaction (pacs.008) message");
                processPaymentTransaction(jsonString);
            } else {
                log.warn("Unknown message type received from Kafka. JSON keys: {}", jsonNode.fieldNames());
            }
            
        } catch (Exception e) {
            log.error("Error processing message from Kafka", e);
            // Consider implementing dead letter queue or retry logic here
        }
    }

    private void processPaymentTransaction(String jsonString) {
        try {
            FIToFICustomerCreditTransfer request = objectMapper.readValue(
                    jsonString, 
                    FIToFICustomerCreditTransfer.class
            );
            
            PaymentBatch paymentBatch = paymentTransactionMapper.fromRegulatoryRequest(request);
            String ispb = extractIspbFromTransaction(request);
            
            log.info("Processing payment transaction batch for ISPB: {}, transactions: {}", 
                    ispb, paymentBatch.getTransactions().size());
            
            paymentTransactionProcessorUseCase.processTransactionBatch(ispb, paymentBatch);
            
        } catch (Exception e) {
            log.error("Error processing payment transaction from Kafka", e);
            throw new RuntimeException("Failed to process payment transaction", e);
        }
    }

    private void processStatusReport(String jsonString) {
        try {
            FIToFIPaymentStatusReport statusReport = objectMapper.readValue(
                    jsonString, 
                    FIToFIPaymentStatusReport.class
            );
            
            StatusBatch statusBatch = statusReportMapper.fromRegulatoryReport(statusReport);
            String ispb = extractIspbFromStatusReport(statusReport);
            
            log.info("Processing status report batch for ISPB: {}, reports: {}", 
                    ispb, statusBatch.getStatusReports().size());
            
            paymentTransactionProcessorUseCase.processStatusBatch(ispb, statusBatch);
            
        } catch (Exception e) {
            log.error("Error processing status report from Kafka", e);
            throw new RuntimeException("Failed to process status report", e);
        }
    }

    private String extractIspbFromTransaction(FIToFICustomerCreditTransfer request) {
        // Extract ISPB from the creditor agent (receiving bank)
        if (request.getCreditTransferTransactions() != null && !request.getCreditTransferTransactions().isEmpty()) {
            var firstTransaction = request.getCreditTransferTransactions().get(0);
            if (firstTransaction.getCreditorFinancialInstitution() != null 
                    && firstTransaction.getCreditorFinancialInstitution().getFinancialInstitutionIdentification() != null
                    && firstTransaction.getCreditorFinancialInstitution().getFinancialInstitutionIdentification().getClearingSystemMemberIdentification() != null) {
                return firstTransaction.getCreditorFinancialInstitution().getFinancialInstitutionIdentification().getClearingSystemMemberIdentification().getIspb();
            }
        }
        log.warn("Could not extract ISPB from transaction, using default");
        return "00000000";
    }

    private String extractIspbFromStatusReport(FIToFIPaymentStatusReport statusReport) {
        // Extract ISPB from the status report - this might need adjustment based on your domain model
        // For now, returning a placeholder since the structure isn't clear
        log.debug("Extracting ISPB from status report");
        return "00000000";
    }
}
