package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationOrchestratorTest {

    @Test
    void acceptanceRequestsAreGroupedByReceiverIspb() {
        NotificationStorage notificationStorage = mock(NotificationStorage.class);
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(
                notificationStorage,
                new NotificationValidator(),
                new NotificationBuilder()
        );
        PaymentTransaction first = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransaction second = paymentTransaction("E2E-2", "10000002", "20000001");
        PaymentTransaction third = paymentTransaction("E2E-3", "10000003", "20000002");

        orchestrator.sendAcceptanceRequests(List.of(first, second, third));

        verify(notificationStorage).addTransactionNotifications("20000001", List.of(first, second));
        verify(notificationStorage).addTransactionNotifications("20000002", List.of(third));
    }

    @Test
    void confirmationNotificationsAreGroupedByDestinationIspb() {
        NotificationStorage notificationStorage = mock(NotificationStorage.class);
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(
                notificationStorage,
                new NotificationValidator(),
                new NotificationBuilder()
        );
        PaymentTransaction first = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransaction second = paymentTransaction("E2E-2", "10000002", "20000001");

        orchestrator.sendConfirmationNotifications(List.of(first, second));

        verify(notificationStorage).addStatusNotifications("20000001", List.of(
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER),
                status("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)
        ));
        verify(notificationStorage).addStatusNotifications("10000001", List.of(
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
        ));
        verify(notificationStorage).addStatusNotifications("10000002", List.of(
                status("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
        ));
    }

    @Test
    void rejectionNotificationsAreGroupedBySenderIspb() {
        NotificationStorage notificationStorage = mock(NotificationStorage.class);
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(
                notificationStorage,
                new NotificationValidator(),
                new NotificationBuilder()
        );
        PaymentTransaction first = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransaction second = paymentTransaction("E2E-2", "10000001", "20000002");

        orchestrator.sendRejectionNotifications(List.of(first, second));

        verify(notificationStorage).addStatusNotifications("10000001", List.of(
                status("E2E-1", PaymentStatus.REJECTED),
                status("E2E-2", PaymentStatus.REJECTED)
        ));
    }

    private static StatusReport status(String paymentId, PaymentStatus status) {
        return StatusReport.builder()
                .originalPaymentId(paymentId)
                .status(status)
                .build();
    }

    private static PaymentTransaction paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransaction.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .sender(party(senderBankCode))
                .receiver(party(receiverBankCode))
                .build();
    }

    private static Party party(String bankCode) {
        return Party.builder()
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number(1L)
                        .branch(1)
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
