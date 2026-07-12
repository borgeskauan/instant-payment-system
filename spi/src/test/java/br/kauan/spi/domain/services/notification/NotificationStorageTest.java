package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.payload.NotificationPayloadFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationStorageTest {

    @Test
    void storageDoesNotExposeSingleItemEntryPoints() {
        assertThrows(NoSuchMethodException.class,
                () -> NotificationStorage.class.getMethod("addStatusNotification", String.class, StatusReportCommand.class));
        assertThrows(NoSuchMethodException.class,
                () -> NotificationStorage.class.getMethod("addStatusNotifications", String.class, List.class));
        assertThrows(NoSuchMethodException.class,
                () -> NotificationStorage.class.getMethod("addTransactionNotification", String.class, PaymentTransactionCommand.class));
    }

    @Test
    void transactionNotificationsPublishOneAcceptanceRequestPayloadPerPayment() {
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationStorage storage = notificationStorage(publisher);

        storage.addTransactionNotifications(Map.of(
                "20000001",
                List.of(
                        paymentTransaction("E2E-1", "10000001", "20000001"),
                        paymentTransaction("E2E-2", "10000002", "20000001")
                )
        ));

        ArgumentCaptor<List<NotificationPublication>> notificationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishNotifications(notificationsCaptor.capture());
        List<NotificationPublication> notifications = notificationsCaptor.getValue();

        assertThat(notifications).hasSize(2);
        assertThat(notifications)
                .extracting(NotificationPublication::ispb)
                .containsExactly("20000001", "20000001");
        assertThat(notifications.get(0).payload())
                .contains("\"NbOfTxs\":1")
                .contains("\"EndToEndId\":\"E2E-1\"")
                .doesNotContain("\"EndToEndId\":\"E2E-2\"")
                .contains("\"Id\":\"000123\"")
                .contains("\"Issr\":\"0012\"");
        assertThat(notifications.get(1).payload())
                .contains("\"NbOfTxs\":1")
                .contains("\"EndToEndId\":\"E2E-2\"")
                .doesNotContain("\"EndToEndId\":\"E2E-1\"");
    }

    @Test
    void statusNotificationsPublishOneStatusPayloadPerReport() {
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationStorage storage = notificationStorage(publisher);

        storage.addStatusNotifications(Map.of(
                "10000001", List.of(
                        status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                        status("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
                ),
                "20000001", List.of(
                        status("E2E-3", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER)
                )
        ));

        ArgumentCaptor<List<NotificationPublication>> notificationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishNotifications(notificationsCaptor.capture());
        List<NotificationPublication> notifications = notificationsCaptor.getValue();

        assertThat(notifications).hasSize(3);
        assertThat(notifications)
                .extracting(NotificationPublication::ispb)
                .containsExactlyInAnyOrder("10000001", "10000001", "20000001");
        assertThat(notifications)
                .filteredOn(notification -> notification.payload().contains("\"OrgnlEndToEndId\":\"E2E-1\""))
                .singleElement()
                .satisfies(notification -> assertThat(notification.payload())
                        .contains("\"NbOfTxs\":1")
                        .doesNotContain("\"OrgnlEndToEndId\":\"E2E-2\""));
        assertThat(notifications)
                .filteredOn(notification -> notification.payload().contains("\"OrgnlEndToEndId\":\"E2E-2\""))
                .singleElement()
                .satisfies(notification -> assertThat(notification.payload())
                        .contains("\"NbOfTxs\":1")
                        .doesNotContain("\"OrgnlEndToEndId\":\"E2E-1\""));
        assertThat(notifications)
                .filteredOn(notification -> notification.ispb().equals("20000001"))
                .singleElement()
                .satisfies(notification -> assertThat(notification.payload())
                        .contains("\"NbOfTxs\":1")
                        .contains("\"OrgnlEndToEndId\":\"E2E-3\""));
    }

    private static NotificationStorage notificationStorage(NotificationPublisher publisher) {
        return new NotificationStorage(
                new NotificationPayloadFactory(),
                publisher
        );
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
                        .number("000123")
                        .branch("0012")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
