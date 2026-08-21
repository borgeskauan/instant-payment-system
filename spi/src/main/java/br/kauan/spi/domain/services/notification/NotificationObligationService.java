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
import java.util.List;

import static br.kauan.spi.Utils.getBankCode;

@Slf4j
@Service
public class NotificationObligationService {

    private static final String ACCEPTANCE_REQUEST = "ACCEPTANCE_REQUEST";
    private static final String REJECTED_NOTIFICATION = "REJECTED_NOTIFICATION";
    private static final String SETTLED_NOTIFICATION = "SETTLED_NOTIFICATION";

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

        List<NotificationPublication> obligations = new ArrayList<>(paymentTransactions.size());
        for (PaymentTransactionCommand paymentTransaction : paymentTransactions) {
            validatePaymentTransaction(paymentTransaction);
            String receiverIspb = validatedIspb(getBankCode(paymentTransaction.getReceiver()));
            byte[] payload = contentSerializer.serialize(
                    payloadFactory.paymentNotification(List.of(paymentTransaction))
            );
            obligations.add(NotificationPublication.create(
                    receiverIspb,
                    payload,
                    ACCEPTANCE_REQUEST,
                    paymentTransaction.getPaymentId(),
                    null
            ));
        }

        store(obligations);
        log.debug("Acceptance notification obligations stored. payments={}", paymentTransactions.size());
    }

    public void storeStatusObligations(
            List<PaymentTransactionCommand> settledPayments,
            List<PaymentRejection> rejectedPayments
    ) {
        if (settledPayments.isEmpty() && rejectedPayments.isEmpty()) {
            return;
        }

        List<NotificationPublication> obligations =
                new ArrayList<>(settledPayments.size() * 2 + rejectedPayments.size());

        for (PaymentTransactionCommand paymentTransaction : settledPayments) {
            validatePaymentTransaction(paymentTransaction);
            String receiverIspb = validatedIspb(getBankCode(paymentTransaction.getReceiver()));
            String senderIspb = validatedIspb(getBankCode(paymentTransaction.getSender()));
            obligations.add(statusObligation(
                    paymentTransaction,
                    receiverIspb,
                    PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER,
                    null
            ));
            obligations.add(statusObligation(
                    paymentTransaction,
                    senderIspb,
                    PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER,
                    null
            ));
        }

        for (PaymentRejection rejection : rejectedPayments) {
            PaymentTransactionCommand paymentTransaction = rejection.payment();
            validatePaymentTransaction(paymentTransaction);
            String senderIspb = validatedIspb(getBankCode(paymentTransaction.getSender()));
            obligations.add(statusObligation(
                    paymentTransaction,
                    senderIspb,
                    PaymentStatus.REJECTED,
                    rejection.reason()
            ));
        }

        store(obligations);
        log.debug(
                "Status notification obligations stored. settled={}, rejected={}",
                settledPayments.size(),
                rejectedPayments.size()
        );
    }

    private NotificationPublication statusObligation(
            PaymentTransactionCommand paymentTransaction,
            String recipientIspb,
            PaymentStatus paymentStatus,
            PaymentRejectionReason rejectionReason
    ) {
        StatusReportCommand statusReport = StatusReportCommand.builder()
                .originalPaymentId(paymentTransaction.getPaymentId())
                .status(paymentStatus)
                .reasons(notificationReasons(rejectionReason))
                .build();
        byte[] payload = contentSerializer.serialize(
                payloadFactory.statusNotification(List.of(statusReport))
        );
        return NotificationPublication.create(
                recipientIspb,
                payload,
                paymentStatus == PaymentStatus.REJECTED ? REJECTED_NOTIFICATION : SETTLED_NOTIFICATION,
                paymentTransaction.getPaymentId(),
                notificationStatus(paymentStatus)
        );
    }

    private void store(List<NotificationPublication> obligations) {
        List<NotificationPublication> inserted = outboundNotificationRepository.insertAll(obligations);
        if (!inserted.isEmpty()) {
            eventPublisher.publishEvent(new OutboundNotificationBatchReady(inserted));
        }
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

    private String notificationStatus(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> "ACCC";
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> "ACSC";
            case REJECTED -> "RJCT";
            case ACCEPTED_IN_PROCESS, WAITING_ACCEPTANCE, ACCEPTED_AND_SETTLED ->
                    throw new IllegalArgumentException("No notification status for: " + paymentStatus);
        };
    }
}
