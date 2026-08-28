package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentOutcomeServiceTest {

    private PaymentStore paymentStore;
    private PspStateStore stateStore;
    private PaymentOutcomeService service;

    @BeforeEach
    void setUp() {
        paymentStore = mock(PaymentStore.class);
        stateStore = mock(PspStateStore.class);
        service = new PaymentOutcomeService(paymentStore, stateStore);
    }

    @Test
    void senderFinalOutcomeReplayDebitsLocalBalanceOnlyOnce() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))
                .thenReturn(true, false);

        service.handleStatuses(List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)));
        service.handleStatuses(List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)));

        ArgumentCaptor<Map<BankAccountId, BigDecimal>> deltas = ArgumentCaptor.captor();
        verify(stateStore, times(1)).applyBalanceDeltas(deltas.capture());
        assertThat(deltas.getValue()).containsEntry(payment.getSender().getAccount().getId(), new BigDecimal("-10.00"));
        verify(paymentStore).markFinalStatusApplied("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
    }

    @Test
    void receiverFinalOutcomeReplayCreditsLocalBalanceOnlyOnce() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER))
                .thenReturn(true, false);

        service.handleStatuses(List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)));
        service.handleStatuses(List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)));

        ArgumentCaptor<Map<BankAccountId, BigDecimal>> deltas = ArgumentCaptor.captor();
        verify(stateStore, times(1)).applyBalanceDeltas(deltas.capture());
        assertThat(deltas.getValue()).containsEntry(payment.getReceiver().getAccount().getId(), new BigDecimal("10.00"));
    }

    @Test
    void senderAndReceiverOutcomesUseIndependentIdempotencyKeys() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)).thenReturn(true);
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)).thenReturn(true);

        service.handleStatuses(List.of(
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)
        ));

        ArgumentCaptor<Map<BankAccountId, BigDecimal>> deltas = ArgumentCaptor.captor();
        verify(stateStore).applyBalanceDeltas(deltas.capture());
        assertThat(deltas.getValue())
                .containsEntry(payment.getSender().getAccount().getId(), new BigDecimal("-10.00"))
                .containsEntry(payment.getReceiver().getAccount().getId(), new BigDecimal("10.00"));
    }

    @Test
    void duplicateFinalOutcomesInSameBatchApplyOnlyOnce() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))
                .thenReturn(true, false);

        service.handleStatuses(List.of(
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
        ));

        ArgumentCaptor<Map<BankAccountId, BigDecimal>> deltas = ArgumentCaptor.captor();
        verify(stateStore).applyBalanceDeltas(deltas.capture());
        assertThat(deltas.getValue()).containsEntry(payment.getSender().getAccount().getId(), new BigDecimal("-10.00"));
    }

    @Test
    void failedBalanceUpdateReleasesFinalOutcomeClaim() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)).thenReturn(true);
        doThrow(new IllegalStateException("balance update failed")).when(stateStore).applyBalanceDeltas(anyMap());

        assertThatThrownBy(() -> service.handleStatuses(
                List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))))
                .isInstanceOf(IllegalStateException.class);

        verify(paymentStore).releaseFinalStatusClaim("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
        verify(paymentStore, never()).markFinalStatusApplied("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
    }

    @Test
    void rejectedOutcomeUpdatesThePaymentWithoutChangingTheBalance() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));
        when(paymentStore.claimFinalStatus("E2E-1", PaymentStatus.REJECTED)).thenReturn(true);

        service.handleStatuses(List.of(status("E2E-1", PaymentStatus.REJECTED)));

        verify(paymentStore).markFinalStatusApplied("E2E-1", PaymentStatus.REJECTED);
        verifyNoInteractions(stateStore);
    }

    @Test
    void unknownPaymentFailsBeforeAnyFinalOutcomeIsClaimed() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentStore.findAllByIds(anyList())).thenReturn(List.of(payment));

        assertThatThrownBy(() -> service.handleStatuses(List.of(
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                status("unknown", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
        ))).isInstanceOf(IllegalArgumentException.class);

        verify(paymentStore, never()).claimFinalStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
        verifyNoInteractions(stateStore);
    }

    private static StatusReport status(String paymentId, PaymentStatus status) {
        return StatusReport.builder()
                .originalPaymentId(paymentId)
                .status(status)
                .build();
    }

    private static PaymentTransaction payment(String paymentId) {
        return PaymentTransaction.builder()
                .paymentId(paymentId)
                .amount(new BigDecimal("10.00"))
                .sender(party("sender", "10000001"))
                .receiver(party("receiver", "20000001"))
                .build();
    }

    private static Party party(String name, String bankCode) {
        return Party.builder()
                .account(BankAccount.builder()
                        .id(BankAccountId.builder()
                                .accountNumber(name + "-account")
                                .agencyNumber("0001")
                                .bankCode(bankCode)
                                .build())
                        .build())
                .build();
    }
}
