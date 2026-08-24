package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.notification.OutboundNotificationBatchReady;
import br.kauan.spi.adapter.output.notification.OutboundNotificationRepository;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.Reason;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.payload.NotificationPayloadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.kauan.spi.Utils.getBankCode;

@Slf4j
@Service
public class NotificationObligationService {

    static final int MAX_ITEMS_PER_MESSAGE = 15;

    private final NotificationPayloadFactory payloadFactory;
    private final NotificationContentSerializer contentSerializer;
    private final OutboundNotificationRepository outboundNotificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationObligationService(
            NotificationPayloadFactory payloadFactory,
            NotificationContentSerializer contentSerializer,
            OutboundNotificationRepository outboundNotificationRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.payloadFactory = payloadFactory;
        this.contentSerializer = contentSerializer;
        this.outboundNotificationRepository = outboundNotificationRepository;
        this.eventPublisher = eventPublisher;
    }

    public void storeAcceptanceObligations(List<PaymentTransactionCommand> paymentTransactions) {
        if (paymentTransactions.isEmpty()) {
            return;
        }

        store(acceptanceObligations(paymentTransactions));
        log.debug("Acceptance notification obligations stored. payments={}", paymentTransactions.size());
    }

    public void storeTransactionObligations(
            List<PaymentTransactionCommand> acceptanceRequests,
            List<PaymentRejection> rejectedPayments
    ) {
        if (acceptanceRequests.isEmpty() && rejectedPayments.isEmpty()) {
            return;
        }

        List<NotificationPublication> obligations = new ArrayList<>();
        obligations.addAll(acceptanceObligations(acceptanceRequests));
        obligations.addAll(statusObligations(List.of(), rejectedPayments));
        store(obligations);
        log.debug(
                "Transaction notification obligations stored. acceptanceRequests={}, rejected={}",
                acceptanceRequests.size(),
                rejectedPayments.size()
        );
    }

    private List<NotificationPublication> acceptanceObligations(
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        if (paymentTransactions.isEmpty()) {
            return List.of();
        }

        Map<String, List<PaymentTransactionCommand>> byRecipient = new LinkedHashMap<>();
        for (PaymentTransactionCommand paymentTransaction : paymentTransactions) {
            validatePaymentTransaction(paymentTransaction);
            String receiverIspb = validatedIspb(getBankCode(paymentTransaction.getReceiver()));
            byRecipient.computeIfAbsent(receiverIspb, ignored -> new ArrayList<>())
                    .add(paymentTransaction);
        }

        List<NotificationPublication> obligations = new ArrayList<>();
        for (Map.Entry<String, List<PaymentTransactionCommand>> recipient : byRecipient.entrySet()) {
            forEachChunk(recipient.getValue(), chunk -> obligations.add(paymentObligation(
                    recipient.getKey(),
                    chunk
            )));
        }
        return List.copyOf(obligations);
    }

    public void storeStatusObligations(
            List<PaymentTransactionCommand> settledPayments,
            List<PaymentRejection> rejectedPayments
    ) {
        if (settledPayments.isEmpty() && rejectedPayments.isEmpty()) {
            return;
        }

        store(statusObligations(settledPayments, rejectedPayments));
        log.debug(
                "Status notification obligations stored. settled={}, rejected={}",
                settledPayments.size(),
                rejectedPayments.size()
        );
    }

    private List<NotificationPublication> statusObligations(
            List<PaymentTransactionCommand> settledPayments,
            List<PaymentRejection> rejectedPayments
    ) {
        if (settledPayments.isEmpty() && rejectedPayments.isEmpty()) {
            return List.of();
        }

        Map<String, List<StatusReportCommand>> byRecipient = new LinkedHashMap<>();

        for (PaymentTransactionCommand paymentTransaction : settledPayments) {
            validatePaymentTransaction(paymentTransaction);
            String receiverIspb = validatedIspb(getBankCode(paymentTransaction.getReceiver()));
            String senderIspb = validatedIspb(getBankCode(paymentTransaction.getSender()));
            addStatus(byRecipient,
                    receiverIspb,
                    paymentTransaction,
                    PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER,
                    null
            );
            addStatus(byRecipient,
                    senderIspb,
                    paymentTransaction,
                    PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER,
                    null
            );
        }

        for (PaymentRejection rejection : rejectedPayments) {
            PaymentTransactionCommand paymentTransaction = rejection.payment();
            validatePaymentTransaction(paymentTransaction);
            String senderIspb = validatedIspb(getBankCode(paymentTransaction.getSender()));
            addStatus(byRecipient,
                    senderIspb,
                    paymentTransaction,
                    PaymentStatus.REJECTED,
                    rejection.reason()
            );
        }

        List<NotificationPublication> obligations = new ArrayList<>();
        for (Map.Entry<String, List<StatusReportCommand>> recipient : byRecipient.entrySet()) {
            forEachChunk(recipient.getValue(), chunk -> obligations.add(statusObligation(
                    recipient.getKey(),
                    chunk
            )));
        }
        return List.copyOf(obligations);
    }

    private void addStatus(
            Map<String, List<StatusReportCommand>> byRecipient,
            String recipientIspb,
            PaymentTransactionCommand paymentTransaction,
            PaymentStatus paymentStatus,
            PaymentRejectionReason rejectionReason
    ) {
        StatusReportCommand statusReport = StatusReportCommand.builder()
                .originalPaymentId(paymentTransaction.getPaymentId())
                .status(paymentStatus)
                .reasons(notificationReasons(rejectionReason))
                .build();
        byRecipient.computeIfAbsent(recipientIspb, ignored -> new ArrayList<>()).add(statusReport);
    }

    private NotificationPublication paymentObligation(
            String recipientIspb,
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        String messageId = UUID.randomUUID().toString();
        byte[] payload = contentSerializer.serialize(
                payloadFactory.paymentNotification(messageId, paymentTransactions)
        );
        return NotificationPublication.create(
                recipientIspb,
                payload,
                messageId
        );
    }

    private NotificationPublication statusObligation(
            String recipientIspb,
            List<StatusReportCommand> statusReports
    ) {
        String messageId = UUID.randomUUID().toString();
        byte[] payload = contentSerializer.serialize(
                payloadFactory.statusNotification(messageId, statusReports)
        );
        return NotificationPublication.create(
                recipientIspb,
                payload,
                messageId
        );
    }

    private <T> void forEachChunk(List<T> items, java.util.function.Consumer<List<T>> consumer) {
        for (int start = 0; start < items.size(); start += MAX_ITEMS_PER_MESSAGE) {
            consumer.accept(List.copyOf(items.subList(
                    start,
                    Math.min(start + MAX_ITEMS_PER_MESSAGE, items.size())
            )));
        }
    }

    private void store(List<NotificationPublication> obligations) {
        if (obligations.isEmpty()) {
            return;
        }
        outboundNotificationRepository.insertAll(obligations);
        eventPublisher.publishEvent(new OutboundNotificationBatchReady(obligations));
    }

    private List<Reason> notificationReasons(PaymentRejectionReason rejectionReason) {
        if (rejectionReason == null) {
            return null;
        }
        return switch (rejectionReason) {
            case INSUFFICIENT_FUNDS -> List.of(Reason.builder()
                    .code("AM04")
                    .descriptions(List.of())
                    .build());
        };
    }

    private void validatePaymentTransaction(PaymentTransactionCommand paymentTransaction) {
        if (paymentTransaction == null) {
            throw new IllegalArgumentException("Payment transaction cannot be null");
        }
        if (paymentTransaction.getPaymentId() == null) {
            throw new IllegalArgumentException("Payment ID cannot be null");
        }
    }

    private String validatedIspb(String ispb) {
        if (ispb == null || ispb.trim().isEmpty()) {
            throw new IllegalArgumentException("ISPB cannot be null or empty");
        }
        return ispb;
    }

}
