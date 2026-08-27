package br.kauan.spi.domain.services.audit;

import br.kauan.spi.adapter.output.audit.PaymentAuditRepository;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
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

        service.storeAdmissionEvents(List.of(), List.of());
        service.storeOutcomeEvents(List.of(), List.of());

        verifyNoInteractions(repository);
    }

    @Test
    void admissionRecordsTheReservationAndItsFinancialEffect() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand payment = payment("E2E-AUDIT-CREATED", 1_500L);

        service.storeAdmissionEvents(List.of(payment), List.of());

        assertThat(capturedEvents(repository)).containsExactly(new PaymentAuditEvent(
                payment.getPaymentId(),
                PaymentAuditEventType.PAYMENT_RESERVED,
                null,
                PaymentStatus.WAITING_ACCEPTANCE,
                1_500L,
                "11111111",
                "22222222",
                -1_500L,
                null
        ));
    }

    @Test
    void admissionRecordsInsufficientFundsWithoutAReservationDelta() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand payment = payment("E2E-AUDIT-NO-FUNDS", 1_500L);

        service.storeAdmissionEvents(
                List.of(payment),
                List.of(new PaymentRejection(payment, PaymentRejectionReason.INSUFFICIENT_FUNDS))
        );

        assertThat(capturedEvents(repository)).containsExactly(new PaymentAuditEvent(
                payment.getPaymentId(),
                PaymentAuditEventType.PAYMENT_REJECTED,
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
    void outcomesRecordSettlementOrReservationReleaseInOneBulkRepositoryCall() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand settledPayment = payment("E2E-AUDIT-SETTLED", 2_500L);
        PaymentTransactionCommand rejectedPayment = payment("E2E-AUDIT-REJECTED", 1_200L);
        PaymentRejection rejection = new PaymentRejection(rejectedPayment, null);

        service.storeOutcomeEvents(
                List.of(settledPayment),
                List.of(rejection)
        );

        assertThat(capturedEvents(repository)).containsExactlyInAnyOrder(
                new PaymentAuditEvent(
                        settledPayment.getPaymentId(),
                        PaymentAuditEventType.PAYMENT_SETTLED,
                        PaymentStatus.WAITING_ACCEPTANCE,
                        PaymentStatus.ACCEPTED_AND_SETTLED,
                        2_500L,
                        "11111111",
                        "22222222",
                        null,
                        2_500L
                ),
                new PaymentAuditEvent(
                        rejectedPayment.getPaymentId(),
                        PaymentAuditEventType.PAYMENT_REJECTED,
                        PaymentStatus.WAITING_ACCEPTANCE,
                        PaymentStatus.REJECTED,
                        1_200L,
                        "11111111",
                        "22222222",
                        1_200L,
                        null,
                        null
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
