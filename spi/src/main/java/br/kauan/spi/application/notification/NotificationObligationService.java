package br.kauan.spi.application.notification;

import br.kauan.spi.adapter.output.notification.OutboundNotificationRepository;
import br.kauan.spi.application.notification.payload.NotificationPayloadFactory;
import br.kauan.spi.domain.entity.status.NotificationStatus;
import br.kauan.spi.domain.entity.status.NotificationStatusItem;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
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

    public void storeTransactionObligations(
            List<PaymentTransactionCommand> acceptanceRequests,
            List<PaymentRejection> rejectedPayments
    ) {
        if (acceptanceRequests.isEmpty() && rejectedPayments.isEmpty()) {
            return;
        }

        List<OutboundNotification> obligations = new ArrayList<>();
        obligations.addAll(acceptanceObligations(acceptanceRequests));
        obligations.addAll(statusObligations(List.of(), rejectedPayments));
        store(obligations);
        log.debug(
                "Transaction notification obligations stored. acceptanceRequests={}, rejected={}",
                acceptanceRequests.size(),
                rejectedPayments.size()
        );
    }

    private List<OutboundNotification> acceptanceObligations(
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

        List<OutboundNotification> obligations = new ArrayList<>();
        for (Map.Entry<String, List<PaymentTransactionCommand>> recipient : byRecipient.entrySet()) {
            forEachChunk(recipient.getValue(), chunk -> obligations.add(paymentObligation(
                    recipient.getKey(),
                    chunk
            )));
        }
        return List.copyOf(obligations);
    }

    public void storeStatusObligations(
            List<PaymentSettlement> settlements,
            List<PaymentRejection> rejectedPayments
    ) {
        if (settlements.isEmpty() && rejectedPayments.isEmpty()) {
            return;
        }

        store(statusObligations(settlements, rejectedPayments));
        log.debug(
                "Status notification obligations stored. settled={}, rejected={}",
                settlements.size(),
                rejectedPayments.size()
        );
    }

    private List<OutboundNotification> statusObligations(
            List<PaymentSettlement> settlements,
            List<PaymentRejection> rejectedPayments
    ) {
        if (settlements.isEmpty() && rejectedPayments.isEmpty()) {
            return List.of();
        }

        Map<String, List<NotificationStatusItem>> byRecipient = new LinkedHashMap<>();

        for (PaymentSettlement settlement : settlements) {
            PaymentTransactionCommand paymentTransaction = settlement.payment();
            validatePaymentTransaction(paymentTransaction);
            String receiverIspb = validatedIspb(getBankCode(paymentTransaction.getReceiver()));
            String senderIspb = validatedIspb(getBankCode(paymentTransaction.getSender()));
            addStatus(byRecipient,
                    receiverIspb,
                    paymentTransaction,
                    NotificationStatus.ACCC,
                    settlement.reasonCodes()
            );
            addStatus(byRecipient,
                    senderIspb,
                    paymentTransaction,
                    NotificationStatus.ACSC,
                    settlement.reasonCodes()
            );
        }

        for (PaymentRejection rejection : rejectedPayments) {
            PaymentTransactionCommand paymentTransaction = rejection.payment();
            validatePaymentTransaction(paymentTransaction);
            String senderIspb = validatedIspb(getBankCode(paymentTransaction.getSender()));
            addStatus(byRecipient,
                    senderIspb,
                    paymentTransaction,
                    NotificationStatus.RJCT,
                    notificationReasons(rejection)
            );
        }

        List<OutboundNotification> obligations = new ArrayList<>();
        for (Map.Entry<String, List<NotificationStatusItem>> recipient : byRecipient.entrySet()) {
            forEachChunk(recipient.getValue(), chunk -> obligations.add(statusObligation(
                    recipient.getKey(),
                    chunk
            )));
        }
        return List.copyOf(obligations);
    }

    private void addStatus(
            Map<String, List<NotificationStatusItem>> byRecipient,
            String recipientIspb,
            PaymentTransactionCommand paymentTransaction,
            NotificationStatus status,
            List<StatusReasonCode> reasonCodes
    ) {
        NotificationStatusItem statusReport = new NotificationStatusItem(
                paymentTransaction.getPaymentId(),
                status,
                reasonCodes
        );
        byRecipient.computeIfAbsent(recipientIspb, ignored -> new ArrayList<>()).add(statusReport);
    }

    private OutboundNotification paymentObligation(
            String recipientIspb,
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        String messageId = UUID.randomUUID().toString();
        byte[] payload = contentSerializer.serialize(
                payloadFactory.paymentNotification(messageId, paymentTransactions)
        );
        return OutboundNotification.create(
                recipientIspb,
                payload,
                messageId
        );
    }

    private OutboundNotification statusObligation(
            String recipientIspb,
            List<NotificationStatusItem> statusReports
    ) {
        String messageId = UUID.randomUUID().toString();
        byte[] payload = contentSerializer.serialize(
                payloadFactory.statusNotification(messageId, statusReports)
        );
        return OutboundNotification.create(
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

    private void store(List<OutboundNotification> obligations) {
        if (obligations.isEmpty()) {
            return;
        }
        outboundNotificationRepository.insertAll(obligations);
        eventPublisher.publishEvent(new OutboundNotificationBatchReady(obligations));
    }

    private List<StatusReasonCode> notificationReasons(PaymentRejection rejection) {
        if (rejection.cause() == null) {
            return rejection.externalReasonCodes();
        }
        return switch (rejection.cause()) {
            case INSUFFICIENT_FUNDS -> List.of(StatusReasonCode.of("AM04"));
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
