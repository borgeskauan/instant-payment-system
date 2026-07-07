package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationOrchestratorTest {

    @Test
    void orchestratorDoesNotExposeSinglePaymentEntryPoints() {
        assertThrows(NoSuchMethodException.class,
                () -> NotificationOrchestrator.class.getMethod("sendConfirmationNotification", PaymentTransactionCommand.class));
        assertThrows(NoSuchMethodException.class,
                () -> NotificationOrchestrator.class.getMethod("sendRejectionNotification", PaymentTransactionCommand.class));
        assertThrows(NoSuchMethodException.class,
                () -> NotificationOrchestrator.class.getMethod("sendAcceptanceRequest", String.class, PaymentTransactionCommand.class));
    }

    @Test
    void notificationServiceDoesNotExposeSinglePaymentEntryPoints() {
        assertThrows(NoSuchMethodException.class,
                () -> NotificationService.class.getMethod("sendConfirmationNotification", PaymentTransactionCommand.class));
        assertThrows(NoSuchMethodException.class,
                () -> NotificationService.class.getMethod("sendRejectionNotification", PaymentTransactionCommand.class));
        assertThrows(NoSuchMethodException.class,
                () -> NotificationService.class.getMethod("sendAcceptanceRequest", String.class, PaymentTransactionCommand.class));
    }

    @Test
    void acceptanceRequestsAreGroupedByReceiverIspb() {
        NotificationStorage notificationStorage = mock(NotificationStorage.class);
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(
                notificationStorage,
                new NotificationValidator(),
                new NotificationBuilder()
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "10000002", "20000001");
        PaymentTransactionCommand third = paymentTransaction("E2E-3", "10000003", "20000002");

        orchestrator.sendAcceptanceRequests(List.of(first, second, third));

        verify(notificationStorage).addTransactionNotifications(Map.of(
                "20000001", List.of(first, second),
                "20000002", List.of(third)
        ));
    }

    @Test
    void confirmationNotificationsAreGroupedByDestinationIspb() {
        NotificationStorage notificationStorage = mock(NotificationStorage.class);
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(
                notificationStorage,
                new NotificationValidator(),
                new NotificationBuilder()
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "10000002", "20000001");

        orchestrator.sendConfirmationNotifications(List.of(first, second));

        verify(notificationStorage).addStatusNotifications(Map.of(
                "20000001", List.of(
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER),
                        status("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)
                ),
                "10000001", List.of(
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
                ),
                "10000002", List.of(
                        status("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
                )
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
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "10000001", "20000002");

        orchestrator.sendRejectionNotifications(List.of(first, second));

        verify(notificationStorage).addStatusNotifications(Map.of(
                "10000001", List.of(
                        status("E2E-1", PaymentStatus.REJECTED),
                        status("E2E-2", PaymentStatus.REJECTED)
                )
        ));
    }

    private static StatusReportCommand status(String paymentId, PaymentStatus status) {
        return StatusReportCommand.builder()
                .originalPaymentId(paymentId)
                .status(status)
                .build();
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransactionCommand.builder()
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
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
