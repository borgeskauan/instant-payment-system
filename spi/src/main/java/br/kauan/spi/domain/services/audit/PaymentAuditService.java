package br.kauan.spi.domain.services.audit;

import br.kauan.spi.adapter.output.audit.PaymentAuditRepository;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static br.kauan.spi.Utils.getBankCode;

@Service
public class PaymentAuditService {

    private final PaymentAuditRepository auditRepository;

    public PaymentAuditService(PaymentAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void storeAdmissionEvents(
            List<PaymentTransactionCommand> createdPayments,
            List<PaymentRejection> rejectedPayments
    ) {
        if (createdPayments.isEmpty()) {
            if (!rejectedPayments.isEmpty()) {
                throw new IllegalArgumentException("Rejected payments must belong to the created payment set");
            }
            return;
        }

        Map<String, PaymentRejection> rejectionByPaymentId = rejectionsByPaymentId(rejectedPayments);
        List<PaymentAuditEvent> events = new ArrayList<>(createdPayments.size());
        for (PaymentTransactionCommand payment : createdPayments) {
            validatePayment(payment);
            PaymentRejection rejection = rejectionByPaymentId.remove(payment.getPaymentId());
            long amountCents = payment.getAmountCents();
            events.add(new PaymentAuditEvent(
                    payment.getPaymentId(),
                    rejection == null
                            ? PaymentAuditEventType.PAYMENT_RESERVED
                            : PaymentAuditEventType.PAYMENT_REJECTED,
                    null,
                    rejection == null ? PaymentState.WAITING_ACCEPTANCE : PaymentState.REJECTED,
                    amountCents,
                    requiredIspb(getBankCode(payment.getSender())),
                    requiredIspb(getBankCode(payment.getReceiver())),
                    rejection == null ? Math.negateExact(amountCents) : null,
                    null,
                    rejection == null ? null : rejection.cause(),
                    rejection == null ? List.of() : rejection.externalReasonCodes()
            ));
        }
        requireNoUnknownRejections(rejectionByPaymentId);
        auditRepository.insertAll(events);
    }

    public void storeOutcomeEvents(
            List<PaymentSettlement> settlements,
            List<PaymentRejection> rejectedPayments
    ) {
        if (settlements.isEmpty() && rejectedPayments.isEmpty()) {
            return;
        }

        List<PaymentAuditEvent> events = new ArrayList<>(settlements.size() + rejectedPayments.size());
        for (PaymentSettlement settlement : settlements) {
            PaymentTransactionCommand payment = settlement.payment();
            validatePayment(payment);
            long amountCents = payment.getAmountCents();
            events.add(new PaymentAuditEvent(
                    payment.getPaymentId(),
                    PaymentAuditEventType.PAYMENT_SETTLED,
                    PaymentState.WAITING_ACCEPTANCE,
                    PaymentState.SETTLED,
                    amountCents,
                    requiredIspb(getBankCode(payment.getSender())),
                    requiredIspb(getBankCode(payment.getReceiver())),
                    null,
                    amountCents,
                    null,
                    settlement.reasonCodes()
            ));
        }
        for (PaymentRejection rejection : rejectedPayments) {
            PaymentTransactionCommand payment = rejection.payment();
            validatePayment(payment);
            long amountCents = payment.getAmountCents();
            events.add(new PaymentAuditEvent(
                    payment.getPaymentId(),
                    PaymentAuditEventType.PAYMENT_REJECTED,
                    PaymentState.WAITING_ACCEPTANCE,
                    PaymentState.REJECTED,
                    amountCents,
                    requiredIspb(getBankCode(payment.getSender())),
                    requiredIspb(getBankCode(payment.getReceiver())),
                    amountCents,
                    null,
                    rejection.cause(),
                    rejection.externalReasonCodes()
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

    private Map<String, PaymentRejection> rejectionsByPaymentId(List<PaymentRejection> rejectedPayments) {
        Map<String, PaymentRejection> rejectionByPaymentId = new HashMap<>();
        for (PaymentRejection rejection : rejectedPayments) {
            validatePayment(rejection.payment());
            if (rejectionByPaymentId.put(rejection.payment().getPaymentId(), rejection) != null) {
                throw new IllegalArgumentException("Duplicate rejected payment: " + rejection.payment().getPaymentId());
            }
        }
        return rejectionByPaymentId;
    }

    private void requireNoUnknownRejections(Map<String, PaymentRejection> rejectionByPaymentId) {
        if (!rejectionByPaymentId.isEmpty()) {
            throw new IllegalArgumentException("Rejected payments must belong to the created payment set");
        }
    }

    private String requiredIspb(String ispb) {
        if (ispb == null || ispb.isBlank()) {
            throw new IllegalArgumentException("ISPB cannot be null or blank");
        }
        return ispb;
    }
}
