package br.kauan.paymentserviceprovider.adapter.output.listener;

import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.commons.BackgroundTaskRunner;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
public class CentralTransferNotificationListener {

    private final CentralTransferSystemRestClient transferRestClient;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;

    private final BackgroundTaskRunner backgroundTaskRunner;

    public CentralTransferNotificationListener(
            CentralTransferSystemRestClient transferRestClient,
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            BackgroundTaskRunner backgroundTaskRunner
    ) {
        this.transferRestClient = transferRestClient;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.backgroundTaskRunner = backgroundTaskRunner;
    }

    public void startListeningForNotifications(
            Consumer<List<StatusReport>> statusReportHandler,
            Consumer<List<PaymentTransaction>> transferRequestHandler) {
        backgroundTaskRunner.startLoop(() -> receiveNotifications(statusReportHandler, transferRequestHandler));
    }

    private void receiveNotifications(
            Consumer<List<StatusReport>> statusReportHandler,
            Consumer<List<PaymentTransaction>> transferRequestHandler
    ) {
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

    private void handleNotification(
            Object notification,
            Consumer<List<StatusReport>> statusReportHandler,
            Consumer<List<PaymentTransaction>> transferRequestHandler
    ) {
        if (notification instanceof FIToFIPaymentStatusReport statusReport) {
            statusReportHandler.accept(statusReportMapper.fromRegulatoryReport(statusReport));

        } else if (notification instanceof FIToFICustomerCreditTransfer creditTransfer) {
            transferRequestHandler.accept(paymentTransactionMapper.fromRegulatoryRequest(creditTransfer));

        } else {
            log.warn("Received unknown notification type");
        }
    }
}
