package br.kauan.spi.domain.services.audit;

import br.kauan.spi.adapter.output.audit.PaymentAuditRepository;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentStatusTransition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static br.kauan.spi.Utils.getBankCode;

@Service
public class PaymentAuditService {

    private final PaymentAuditRepository auditRepository;

    public PaymentAuditService(PaymentAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void storeCreationEvents(List<PaymentTransactionCommand> createdPayments) {
        if (createdPayments.isEmpty()) {
            return;
        }

        List<PaymentAuditEvent> events = new ArrayList<>(createdPayments.size());
        for (PaymentTransactionCommand payment : createdPayments) {
            validatePayment(payment);
            events.add(new PaymentAuditEvent(
                    payment.getPaymentId(),
                    PaymentAuditEventType.PAYMENT_CREATED,
                    null,
                    PaymentStatus.WAITING_ACCEPTANCE,
                    payment.getAmountCents(),
                    requiredIspb(getBankCode(payment.getSender())),
                    requiredIspb(getBankCode(payment.getReceiver())),
                    null,
                    null
            ));
        }
        auditRepository.insertAll(events);
    }

    public void storeStatusEvents(
            List<PaymentStatusTransition> appliedStatusTransitions,
            List<PaymentTransactionCommand> settledPayments
    ) {
        if (appliedStatusTransitions.isEmpty() && settledPayments.isEmpty()) {
            return;
        }

        List<PaymentAuditEvent> events =
                new ArrayList<>(appliedStatusTransitions.size() + settledPayments.size());
        for (PaymentStatusTransition transition : appliedStatusTransitions) {
            events.add(new PaymentAuditEvent(
                    transition.paymentId(),
                    PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
                    transition.previousStatus(),
                    transition.resultingStatus(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    transition.rejectionReason()
            ));
        }
        for (PaymentTransactionCommand payment : settledPayments) {
            validatePayment(payment);
            long amountCents = payment.getAmountCents();
            events.add(new PaymentAuditEvent(
                    payment.getPaymentId(),
                    PaymentAuditEventType.SETTLEMENT_APPLIED,
                    null,
                    null,
                    amountCents,
                    requiredIspb(getBankCode(payment.getSender())),
                    requiredIspb(getBankCode(payment.getReceiver())),
                    Math.negateExact(amountCents),
                    amountCents
            ));
        }
        auditRepository.insertAll(events);
    }

    private void validatePayment(PaymentTransactionCommand payment) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment cannot be null");
        }
        if (payment.getPaymentId() == null || payment.getPaymentId().isBlank()) {
            throw new IllegalArgumentException("Payment ID cannot be null or blank");
        }
    }

    private String requiredIspb(String ispb) {
        if (ispb == null || ispb.isBlank()) {
            throw new IllegalArgumentException("ISPB cannot be null or blank");
        }
        return ispb;
    }
}
