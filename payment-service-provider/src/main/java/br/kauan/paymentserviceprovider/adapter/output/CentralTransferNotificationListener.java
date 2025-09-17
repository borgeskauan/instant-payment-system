package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.adapter.output.pacs.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Slf4j
@Service
public class CentralTransferNotificationListener {

    private final CentralTransferRestClient transferRestClient;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;

    private final InfiniteLoopService infiniteLoopService;

    public CentralTransferNotificationListener(CentralTransferRestClient transferRestClient,
                                               PaymentTransactionMapper paymentTransactionMapper,
                                               StatusReportMapper statusReportMapper,
                                               InfiniteLoopService infiniteLoopService) {
        this.transferRestClient = transferRestClient;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.infiniteLoopService = infiniteLoopService;
    }

    public void startListeningForNotifications(Consumer<StatusBatch> statusReportHandler,
                                               Consumer<PaymentBatch> transferRequestHandler) {
        infiniteLoopService.startLoop(() -> receiveNotifications(statusReportHandler, transferRequestHandler));
    }

    private void receiveNotifications(Consumer<StatusBatch> statusReportHandler, Consumer<PaymentBatch> transferRequestHandler) {
        var notifications = transferRestClient.getMessages(GlobalVariables.getBankCode()).getContent();

        log.info("Received {} notifications from central transfer service", notifications.size());

        if (notifications.isEmpty()) {
            return;
        }

        log.info(notifications.toString());

        for (var notification : notifications) {
            var parsedNotification = parseNotification(notification);
            handleNotification(parsedNotification, statusReportHandler, transferRequestHandler);
        }
    }

    private Object parseNotification(String notification) {
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            JsonNode root = objectMapper.readTree(notification);

            if (root.has("TxInfAndSts")) {
                return objectMapper.treeToValue(root, FIToFIPaymentStatusReport.class);
            } else if (root.has("CdtTrfTxInf")) {
                return objectMapper.treeToValue(root, FIToFICustomerCreditTransfer.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Error parsing notification: {}", e.getMessage());
        }

        return null;
    }

    private void handleNotification(Object notification, Consumer<StatusBatch> statusReportHandler, Consumer<PaymentBatch> transferRequestHandler) {
        if (notification instanceof FIToFIPaymentStatusReport statusReport) {
            var statusBatch = statusReportMapper.fromRegulatoryReport(statusReport);
            statusReportHandler.accept(statusBatch);

        } else if (notification instanceof FIToFICustomerCreditTransfer creditTransfer) {
            var paymentBatch = paymentTransactionMapper.fromRegulatoryRequest(creditTransfer);
            transferRequestHandler.accept(paymentBatch);

        } else {
            log.warn("Received unknown notification type");
        }
    }
}
