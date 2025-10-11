package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.InstitutionMessages;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ReactiveNotificationStorage {

    private final Map<String, InstitutionMessages> notifications = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<SpiNotification>> notificationSinks = new ConcurrentHashMap<>();
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final NotificationContentSerializer contentSerializer;

    public ReactiveNotificationStorage(
            StatusReportMapper statusReportMapper,
            PaymentTransactionMapper paymentTransactionMapper
    ) {
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.contentSerializer = new NotificationContentSerializer();
    }

    public Mono<Void> addStatusNotification(String ispb, StatusReport statusReport) {
        return Mono.fromRunnable(() -> {
            InstitutionMessages messages = getMessageContainer(ispb);
            messages.statuses().add(statusReport);

            // Notify any subscribers
            notifySubscribers(ispb, messages);
        });
    }

    public Mono<Void> addTransactionNotification(String ispb, PaymentTransaction paymentTransaction) {
        return Mono.fromRunnable(() -> {
            InstitutionMessages messages = getMessageContainer(ispb);
            messages.transactions().add(paymentTransaction);

            // Notify any subscribers
            notifySubscribers(ispb, messages);
        });
    }

    public Mono<SpiNotification> getNotifications(String ispb) {
        return Mono.defer(() -> {
            InstitutionMessages messages = notifications.remove(ispb);

            if (messages == null || messages.isEmpty()) {
                log.debug("No notifications found for ISPB: {}, creating reactive stream", ispb);
                return createNotificationStream(ispb);
            }

            log.debug("Retrieved {} status reports and {} transactions for ISPB: {}",
                    messages.statuses().size(), messages.transactions().size(), ispb);
            return Mono.just(convertToSpiNotifications(messages));
        });
    }

    private Mono<SpiNotification> createNotificationStream(String ispb) {
        return Mono.create(sink -> {
            Sinks.Many<SpiNotification> notificationSink = notificationSinks.computeIfAbsent(ispb,
                    key -> Sinks.many().unicast().onBackpressureBuffer());

            // Subscribe to the sink and emit when data arrives
            notificationSink.asFlux()
                    .next()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .subscribe(
                            sink::success,
                            error -> {
                                log.debug("Notification stream completed for ISPB: {}", ispb);
                                sink.success(SpiNotification.empty());
                            }
                    );
        });
    }

    private void notifySubscribers(String ispb, InstitutionMessages messages) {
        Sinks.Many<SpiNotification> sink = notificationSinks.get(ispb);
        if (sink != null) {
            SpiNotification notification = convertToSpiNotifications(messages);
            // Clear the messages after converting to notification
            notifications.remove(ispb);
            // Try to emit the notification
            Sinks.EmitResult result = sink.tryEmitNext(notification);
            if (result.isFailure()) {
                log.warn("Failed to emit notification for ISPB: {}, reason: {}", ispb, result);
            } else {
                // Remove the sink after successful emission
                notificationSinks.remove(ispb, sink);
            }
        }
    }

    private SpiNotification convertToSpiNotifications(InstitutionMessages messages) {
        List<String> content = new ArrayList<>();

        Optional.ofNullable(createStatusBatchNotification(messages.statuses()))
                .flatMap(contentSerializer::serialize)
                .ifPresent(content::add);

        Optional.ofNullable(createPaymentBatchNotification(messages.transactions()))
                .flatMap(contentSerializer::serialize)
                .ifPresent(content::add);

        return SpiNotification.builder()
                .content(content)
                .build();
    }

    private FIToFIPaymentStatusReport createStatusBatchNotification(List<StatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return null;
        }

        BatchDetails batchDetails = BatchDetails.of(statusReports.size());
        StatusBatch statusBatch = StatusBatch.builder()
                .batchDetails(batchDetails)
                .statusReports(statusReports)
                .build();

        return statusReportMapper.toRegulatoryReport(statusBatch);
    }

    private FIToFICustomerCreditTransfer createPaymentBatchNotification(List<PaymentTransaction> transactions) {
        if (transactions.isEmpty()) {
            return null;
        }

        BatchDetails batchDetails = BatchDetails.of(transactions.size());
        PaymentBatch paymentBatch = PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(transactions)
                .build();

        return paymentTransactionMapper.toRegulatoryRequest(paymentBatch);
    }

    private InstitutionMessages getMessageContainer(String ispb) {
        return notifications.computeIfAbsent(ispb, key -> new InstitutionMessages());
    }
}