package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.input.dtos.pacs.CodeMapping;
import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationStorageTest {

    @Test
    void transactionNotificationsPublishOnePacs008PayloadWithAllPayments() {
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationStorage storage = notificationStorage(publisher);

        storage.addTransactionNotifications("20000001", List.of(
                paymentTransaction("E2E-1", "10000001", "20000001"),
                paymentTransaction("E2E-2", "10000002", "20000001")
        ));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishNotification(org.mockito.ArgumentMatchers.eq("20000001"), jsonCaptor.capture());

        assertThat(jsonCaptor.getValue())
                .contains("\"NbOfTxs\":2")
                .contains("\"EndToEndId\":\"E2E-1\"")
                .contains("\"EndToEndId\":\"E2E-2\"");
    }

    @Test
    void statusNotificationsPublishOnePacs002PayloadWithAllReports() {
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationStorage storage = notificationStorage(publisher);

        storage.addStatusNotifications("10000001", List.of(
                status("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER),
                status("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
        ));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishNotification(org.mockito.ArgumentMatchers.eq("10000001"), jsonCaptor.capture());

        assertThat(jsonCaptor.getValue())
                .contains("\"NbOfTxs\":2")
                .contains("\"OrgnlEndToEndId\":\"E2E-1\"")
                .contains("\"OrgnlEndToEndId\":\"E2E-2\"");
    }

    private static NotificationStorage notificationStorage(NotificationPublisher publisher) {
        CodeMapping codeMapping = new CodeMapping();
        CommonsMapper commonsMapper = new CommonsMapper();
        return new NotificationStorage(
                new StatusReportMapper(commonsMapper, codeMapping),
                new PaymentTransactionMapper(commonsMapper, codeMapping),
                publisher
        );
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
