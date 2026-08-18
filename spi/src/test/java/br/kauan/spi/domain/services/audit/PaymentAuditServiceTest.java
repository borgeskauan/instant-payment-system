package br.kauan.spi.domain.services.audit;

import br.kauan.spi.adapter.output.audit.PaymentAuditRepository;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentStatusTransition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentAuditServiceTest {

    @Test
    void emptyInputsDoNotAccessTheRepository() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);

        service.storeCreationEvents(List.of(), List.of());
        service.storeStatusEvents(List.of(), List.of());

        verifyNoInteractions(repository);
    }

    @Test
    void creationEventsKeepTheImmutablePaymentFacts() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand payment = payment("E2E-AUDIT-CREATED", 1_500L);

        service.storeCreationEvents(List.of(payment), List.of());

        assertThat(capturedEvents(repository)).containsExactly(new PaymentAuditEvent(
                payment.getPaymentId(),
                PaymentAuditEventType.PAYMENT_CREATED,
                null,
                PaymentStatus.WAITING_ACCEPTANCE,
                1_500L,
                "11111111",
                "22222222",
                null,
                null
        ));
    }

    @Test
    void creationEventsRecordIngressRejectionAsTheOriginalOutcome() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand payment = payment("E2E-AUDIT-NO-FUNDS", 1_500L);

        service.storeCreationEvents(
                List.of(payment),
                List.of(new PaymentRejection(payment, PaymentRejectionReason.INSUFFICIENT_FUNDS))
        );

        assertThat(capturedEvents(repository)).containsExactly(new PaymentAuditEvent(
                payment.getPaymentId(),
                PaymentAuditEventType.PAYMENT_CREATED,
                null,
                PaymentStatus.REJECTED,
                1_500L,
                "11111111",
                "22222222",
                null,
                null,
                PaymentRejectionReason.INSUFFICIENT_FUNDS
        ));
    }

    @Test
    void statusChangesAndSettlementsUseOneBulkRepositoryCall() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand settledPayment = payment("E2E-AUDIT-SETTLED", 2_500L);
        PaymentStatusTransition settledTransition = new PaymentStatusTransition(
                settledPayment.getPaymentId(),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );
        PaymentStatusTransition rejectedTransition = new PaymentStatusTransition(
                "E2E-AUDIT-REJECTED",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.REJECTED,
                PaymentRejectionReason.INSUFFICIENT_FUNDS
        );

        service.storeStatusEvents(
                List.of(settledTransition, rejectedTransition),
                List.of(settledPayment)
        );

        assertThat(capturedEvents(repository)).containsExactlyInAnyOrder(
                new PaymentAuditEvent(
                        settledPayment.getPaymentId(),
                        PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
                        PaymentStatus.WAITING_ACCEPTANCE,
                        PaymentStatus.ACCEPTED_AND_SETTLED,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                new PaymentAuditEvent(
                        rejectedTransition.paymentId(),
                        PaymentAuditEventType.PAYMENT_STATUS_CHANGED,
                        PaymentStatus.WAITING_ACCEPTANCE,
                        PaymentStatus.REJECTED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        PaymentRejectionReason.INSUFFICIENT_FUNDS
                ),
                new PaymentAuditEvent(
                        settledPayment.getPaymentId(),
                        PaymentAuditEventType.SETTLEMENT_APPLIED,
                        null,
                        null,
                        2_500L,
                        "11111111",
                        "22222222",
                        -2_500L,
                        2_500L
                )
        );
    }

    @SuppressWarnings("unchecked")
    private List<PaymentAuditEvent> capturedEvents(PaymentAuditRepository repository) {
        ArgumentCaptor<List<PaymentAuditEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertAll(captor.capture());
        return captor.getValue();
    }

    private PaymentTransactionCommand payment(String paymentId, long amountCents) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(amountCents)
                .sender(party("11111111"))
                .receiver(party("22222222"))
                .build();
    }

    private Party party(String bankCode) {
        return Party.builder()
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
