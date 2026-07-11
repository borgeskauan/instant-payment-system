package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.services.BankAccountPartyService;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    private BankAccountPartyService bankAccountPartyService;
    private PaymentRepository paymentRepository;
    private SettlementService service;

    @BeforeEach
    void setUp() {
        bankAccountPartyService = mock(BankAccountPartyService.class);
        paymentRepository = mock(PaymentRepository.class);
        service = new SettlementService(bankAccountPartyService, paymentRepository);
    }

    @Test
    void senderFinalNotificationReplayDebitsLocalBalanceOnlyOnce() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentRepository.claimFinalStatusApplication("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))
                .thenReturn(true, false);

        service.handleSettlements(
                List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)),
                Map.of("E2E-1", payment)
        );
        service.handleSettlements(
                List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)),
                Map.of("E2E-1", payment)
        );

        verify(bankAccountPartyService, times(1)).removeAmountsFromAccounts(anyMap());
        verify(paymentRepository).markFinalStatusApplied("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
    }

    @Test
    void receiverFinalNotificationReplayCreditsLocalBalanceOnlyOnce() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentRepository.claimFinalStatusApplication("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER))
                .thenReturn(true, false);

        service.handleSettlements(
                List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)),
                Map.of("E2E-1", payment)
        );
        service.handleSettlements(
                List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)),
                Map.of("E2E-1", payment)
        );

        verify(bankAccountPartyService, times(1)).addAmountsToAccounts(anyMap());
        verify(paymentRepository).markFinalStatusApplied("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
    }

    @Test
    void senderAndReceiverFinalNotificationsUseIndependentIdempotencyKeys() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentRepository.claimFinalStatusApplication("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))
                .thenReturn(true);
        when(paymentRepository.claimFinalStatusApplication("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER))
                .thenReturn(true);

        service.handleSettlements(
                List.of(
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)
                ),
                Map.of("E2E-1", payment)
        );

        verify(bankAccountPartyService).removeAmountsFromAccounts(anyMap());
        verify(bankAccountPartyService).addAmountsToAccounts(anyMap());
    }

    @Test
    void duplicateFinalNotificationsInSameBatchApplyOnlyOnce() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentRepository.claimFinalStatusApplication("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))
                .thenReturn(true, false);

        service.handleSettlements(
                List.of(
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
                ),
                Map.of("E2E-1", payment)
        );

        ArgumentCaptor<Map<BankAccountId, BigDecimal>> debits = ArgumentCaptor.captor();
        verify(bankAccountPartyService).removeAmountsFromAccounts(debits.capture());
        assertThat(debits.getValue()).containsEntry(payment.getSender().getAccount().getId(), payment.getAmount());
    }

    @Test
    void failedBalanceUpdateReleasesFinalStatusClaimForRetry() {
        PaymentTransaction payment = payment("E2E-1");
        when(paymentRepository.claimFinalStatusApplication("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER))
                .thenReturn(true);
        doThrow(new IllegalStateException("balance update failed"))
                .when(bankAccountPartyService).removeAmountsFromAccounts(anyMap());

        assertThatThrownBy(() -> service.handleSettlements(
                List.of(status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)),
                Map.of("E2E-1", payment)
        )).isInstanceOf(IllegalStateException.class);

        verify(paymentRepository).releaseFinalStatusApplicationClaim("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
        verify(paymentRepository, never()).markFinalStatusApplied("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);
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
