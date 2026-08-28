package br.kauan.spi.application.audit;

import br.kauan.spi.adapter.output.audit.PaymentAuditRepository;
import br.kauan.spi.domain.entity.audit.PaymentAuditEvent;
import br.kauan.spi.domain.entity.audit.PaymentAuditEventType;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
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
                PaymentState.WAITING_ACCEPTANCE,
                1_500L,
                "11111111",
                "22222222",
                -1_500L,
                null,
                null,
                List.of()
        ));
    }

    @Test
    void admissionRecordsInsufficientFundsWithoutAReservationDelta() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand payment = payment("E2E-AUDIT-NO-FUNDS", 1_500L);

        service.storeAdmissionEvents(
                List.of(payment),
                List.of(PaymentRejection.insufficientFunds(payment))
        );

        assertThat(capturedEvents(repository)).containsExactly(new PaymentAuditEvent(
                payment.getPaymentId(),
                PaymentAuditEventType.PAYMENT_REJECTED,
                null,
                PaymentState.REJECTED,
                1_500L,
                "11111111",
                "22222222",
                null,
                null,
                PaymentRejectionCause.INSUFFICIENT_FUNDS,
                List.of()
        ));
    }

    @Test
    void outcomesRecordSettlementOrReservationReleaseInOneBulkRepositoryCall() {
        PaymentAuditRepository repository = mock(PaymentAuditRepository.class);
        PaymentAuditService service = new PaymentAuditService(repository);
        PaymentTransactionCommand settledPayment = payment("E2E-AUDIT-SETTLED", 2_500L);
        PaymentTransactionCommand rejectedPayment = payment("E2E-AUDIT-REJECTED", 1_200L);
        PaymentRejection rejection = PaymentRejection.receiverRejected(
                rejectedPayment,
                List.of(StatusReasonCode.of("AB03"))
        );

        service.storeOutcomeEvents(
                List.of(new PaymentSettlement(settledPayment, List.of(StatusReasonCode.of("AC01")))),
                List.of(rejection)
        );

        assertThat(capturedEvents(repository)).containsExactlyInAnyOrder(
                new PaymentAuditEvent(
                        settledPayment.getPaymentId(),
                        PaymentAuditEventType.PAYMENT_SETTLED,
                        PaymentState.WAITING_ACCEPTANCE,
                        PaymentState.SETTLED,
                        2_500L,
                        "11111111",
                        "22222222",
                        null,
                        2_500L,
                        null,
                        List.of(StatusReasonCode.of("AC01"))
                ),
                new PaymentAuditEvent(
                        rejectedPayment.getPaymentId(),
                        PaymentAuditEventType.PAYMENT_REJECTED,
                        PaymentState.WAITING_ACCEPTANCE,
                        PaymentState.REJECTED,
                        1_200L,
                        "11111111",
                        "22222222",
                        1_200L,
                        null,
                        null,
                        List.of(StatusReasonCode.of("AB03"))
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
