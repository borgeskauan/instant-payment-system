package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.outbox.NotificationOutboxRepository;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.payload.NotificationPayloadFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationObligationServiceTest {

    @Test
    void emptyInputsDoNotTouchTheOutbox() {
        NotificationOutboxRepository outboxRepository = mock(NotificationOutboxRepository.class);
        NotificationObligationService service = service(outboxRepository);

        service.storeAcceptanceObligations(List.of());
        service.storeStatusObligations(List.of(), List.of());

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void acceptanceRequestsBecomeOneReceiverObligationPerPaymentInOneBulkInsert() {
        NotificationOutboxRepository outboxRepository = mock(NotificationOutboxRepository.class);
        NotificationObligationService service = service(outboxRepository);
        PaymentTransactionCommand first = payment("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand second = payment("E2E-2", "10000002", "20000002");

        service.storeAcceptanceObligations(List.of(first, second));

        List<NotificationPublication> obligations = capturedObligations(outboxRepository);
        assertThat(obligations)
                .extracting(
                        NotificationPublication::recipientIspb,
                        NotificationPublication::eventType,
                        NotificationPublication::paymentId,
                        NotificationPublication::status
                )
                .containsExactly(
                        tuple("20000001", "ACCEPTANCE_REQUEST", "E2E-1", null),
                        tuple("20000002", "ACCEPTANCE_REQUEST", "E2E-2", null)
                );
        assertThat(payload(obligations.get(0)))
                .contains("\"NbOfTxs\":1")
                .contains("\"EndToEndId\":\"E2E-1\"")
                .doesNotContain("\"EndToEndId\":\"E2E-2\"");
        assertThat(payload(obligations.get(1)))
                .contains("\"NbOfTxs\":1")
                .contains("\"EndToEndId\":\"E2E-2\"")
                .doesNotContain("\"EndToEndId\":\"E2E-1\"");
        assertThat(obligations)
                .extracting(NotificationPublication::communicationId)
                .allSatisfy(communicationId -> assertThat(communicationId).startsWith("v1:"));
    }

    @Test
    void settledAndRejectedPaymentsBecomeOneCombinedBulkInsert() {
        NotificationOutboxRepository outboxRepository = mock(NotificationOutboxRepository.class);
        NotificationObligationService service = service(outboxRepository);
        PaymentTransactionCommand settled = payment("E2E-SETTLED", "10000001", "20000001");
        PaymentTransactionCommand rejected = payment("E2E-REJECTED", "10000002", "20000002");

        service.storeStatusObligations(List.of(settled), List.of(new PaymentRejection(rejected, null)));

        List<NotificationPublication> obligations = capturedObligations(outboxRepository);
        assertThat(obligations)
                .extracting(
                        NotificationPublication::recipientIspb,
                        NotificationPublication::eventType,
                        NotificationPublication::paymentId,
                        NotificationPublication::status
                )
                .containsExactly(
                        tuple("20000001", "SETTLED_NOTIFICATION", "E2E-SETTLED", "ACCC"),
                        tuple("10000001", "SETTLED_NOTIFICATION", "E2E-SETTLED", "ACSC"),
                        tuple("10000002", "REJECTED_NOTIFICATION", "E2E-REJECTED", "RJCT")
                );
        assertThat(obligations)
                .allSatisfy(obligation -> assertThat(payload(obligation)).contains("\"NbOfTxs\":1"));
        assertThat(payload(obligations.get(0))).contains("\"TxSts\":\"ACCC\"");
        assertThat(payload(obligations.get(1))).contains("\"TxSts\":\"ACSC\"");
        assertThat(payload(obligations.get(2))).contains("\"TxSts\":\"RJCT\"");
    }

    @Test
    void insufficientFundsRejectionUsesAm04InThePayerRjctObligation() {
        NotificationOutboxRepository outboxRepository = mock(NotificationOutboxRepository.class);
        NotificationObligationService service = service(outboxRepository);
        PaymentTransactionCommand rejected = payment("E2E-INSUFFICIENT", "10000001", "20000001");

        service.storeStatusObligations(
                List.of(),
                List.of(new PaymentRejection(rejected, PaymentRejectionReason.INSUFFICIENT_FUNDS))
        );

        List<NotificationPublication> obligations = capturedObligations(outboxRepository);
        assertThat(obligations)
                .extracting(
                        NotificationPublication::recipientIspb,
                        NotificationPublication::eventType,
                        NotificationPublication::paymentId,
                        NotificationPublication::status
                )
                .containsExactly(tuple("10000001", "REJECTED_NOTIFICATION", "E2E-INSUFFICIENT", "RJCT"));
        assertThat(payload(obligations.getFirst()))
                .contains("\"TxSts\":\"RJCT\"")
                .contains("\"Cd\":\"AM04\"")
                .doesNotContain("\"Cd\":\"AB03\"");
    }

    @Test
    void databaseResourceFailurePropagatesWithoutNotificationWrapper() {
        NotificationOutboxRepository outboxRepository = mock(NotificationOutboxRepository.class);
        NotificationObligationService service = service(outboxRepository);
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("database unavailable");
        doThrow(databaseFailure).when(outboxRepository).insertAll(anyList());

        assertThatThrownBy(() -> service.storeAcceptanceObligations(List.of(
                payment("E2E-1", "10000001", "20000001")
        ))).isSameAs(databaseFailure);
    }

    private NotificationObligationService service(NotificationOutboxRepository outboxRepository) {
        return new NotificationObligationService(
                new NotificationPayloadFactory(),
                new NotificationContentSerializer(new ObjectMapper().findAndRegisterModules()),
                outboxRepository
        );
    }

    private List<NotificationPublication> capturedObligations(NotificationOutboxRepository outboxRepository) {
        ArgumentCaptor<List<NotificationPublication>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository).insertAll(captor.capture());
        return captor.getValue();
    }

    private String payload(NotificationPublication notification) {
        return new String(notification.payload(), StandardCharsets.UTF_8);
    }

    private PaymentTransactionCommand payment(String paymentId, String senderIspb, String receiverIspb) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1_000L)
                .currency("BRL")
                .description("notification obligation test")
                .sender(party(senderIspb))
                .receiver(party(receiverIspb))
                .build();
    }

    private Party party(String ispb) {
        return Party.builder()
                .name("Name")
                .taxId("123")
                .pixKey("pix-" + ispb)
                .account(BankAccount.builder()
                        .bankCode(ispb)
                        .number("000123")
                        .branch("0012")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
