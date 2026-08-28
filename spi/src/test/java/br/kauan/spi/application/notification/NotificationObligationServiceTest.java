package br.kauan.spi.application.notification;

import br.kauan.spi.application.notification.payload.NotificationPayloadFactory;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.entity.transfer.PaymentReference;
import br.kauan.spi.port.output.OutboundNotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationObligationServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void emptyInputsDoNotStoreOutboundNotifications() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);

        service.storeTransactionObligations(List.of(), List.of());
        service.storeStatusObligations(List.of(), List.of());

        verifyNoInteractions(repository);
    }

    @Test
    void acceptanceRequestsForDifferentReceiversBecomeSeparateMessagesInOneBulkInsert() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);
        PaymentTransactionCommand first = payment("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand second = payment("E2E-2", "10000002", "20000002");

        service.storeTransactionObligations(List.of(first, second), List.of());

        List<OutboundNotification> obligations = capturedObligations(repository);
        assertThat(obligations)
                .extracting(OutboundNotification::recipientIspb)
                .containsExactly("20000001", "20000002");
        assertThat(payload(obligations.get(0)))
                .contains("\"NbOfTxs\":1")
                .contains("\"EndToEndId\":\"E2E-1\"")
                .doesNotContain("\"EndToEndId\":\"E2E-2\"");
        assertThat(payload(obligations.get(1)))
                .contains("\"NbOfTxs\":1")
                .contains("\"EndToEndId\":\"E2E-2\"")
                .doesNotContain("\"EndToEndId\":\"E2E-1\"");
        assertThat(obligations)
                .extracting(OutboundNotification::communicationId)
                .allSatisfy(communicationId -> assertThatCode(
                        () -> java.util.UUID.fromString(communicationId)
                ).doesNotThrowAnyException());
    }

    @Test
    void settledAndRejectedPaymentsBecomeOneCombinedBulkInsert() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);
        PaymentTransactionCommand settled = payment("E2E-SETTLED", "10000001", "20000001");
        PaymentTransactionCommand rejected = payment("E2E-REJECTED", "10000002", "20000002");

        service.storeStatusObligations(
                List.of(new PaymentSettlement(PaymentReference.from(settled), List.of())),
                List.of(PaymentRejection.receiverRejected(PaymentReference.from(rejected), List.of(StatusReasonCode.of("AB03"))))
        );

        List<OutboundNotification> obligations = capturedObligations(repository);
        assertThat(obligations)
                .extracting(OutboundNotification::recipientIspb)
                .containsExactly("20000001", "10000001", "10000002");
        assertThat(obligations)
                .allSatisfy(obligation -> assertThat(payload(obligation)).contains("\"NbOfTxs\":1"));
        assertThat(payload(obligations.get(0))).contains("\"TxSts\":\"ACCC\"");
        assertThat(payload(obligations.get(1))).contains("\"TxSts\":\"ACSC\"");
        assertThat(payload(obligations.get(2))).contains("\"TxSts\":\"RJCT\"");
    }

    @Test
    void pacs008AcceptanceAndRejectionBecomeOneCombinedBulkInsertAndEvent() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NotificationObligationService service = service(repository, eventPublisher);
        PaymentTransactionCommand accepted = payment("E2E-ACCEPTED", "10000001", "20000001");
        PaymentTransactionCommand rejected = payment("E2E-REJECTED", "10000002", "20000002");

        service.storeTransactionObligations(
                List.of(accepted),
                List.of(PaymentRejection.insufficientFunds(rejected))
        );

        List<OutboundNotification> obligations = capturedObligations(repository);
        assertThat(obligations)
                .extracting(OutboundNotification::recipientIspb)
                .containsExactly("20000001", "10000002");
        assertThat(payload(obligations.getFirst()))
                .contains("\"EndToEndId\":\"E2E-ACCEPTED\"");
        assertThat(payload(obligations.getLast()))
                .contains("\"OrgnlEndToEndId\":\"E2E-REJECTED\"")
                .contains("\"TxSts\":\"RJCT\"")
                .contains("\"Cd\":\"AM04\"");

        ArgumentCaptor<OutboundNotificationBatchReady> event =
                ArgumentCaptor.forClass(OutboundNotificationBatchReady.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().notifications()).containsExactlyElementsOf(obligations);
    }

    @Test
    void insufficientFundsRejectionUsesAm04InThePayerRjctObligation() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);
        PaymentTransactionCommand rejected = payment("E2E-INSUFFICIENT", "10000001", "20000001");

        service.storeStatusObligations(
                List.of(),
                List.of(PaymentRejection.insufficientFunds(rejected))
        );

        List<OutboundNotification> obligations = capturedObligations(repository);
        assertThat(obligations)
                .extracting(OutboundNotification::recipientIspb)
                .containsExactly("10000001");
        assertThat(payload(obligations.getFirst()))
                .contains("\"TxSts\":\"RJCT\"")
                .contains("\"Cd\":\"AM04\"")
                .doesNotContain("\"Cd\":\"AB03\"");
    }

    @Test
    void acceptanceRequestsForOneReceiverAreChunkedAtFifteenItems() throws Exception {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);
        List<PaymentTransactionCommand> payments = new ArrayList<>();
        for (int index = 1; index <= 16; index++) {
            payments.add(payment("E2E-" + index, "10000001", "20000001"));
        }

        service.storeTransactionObligations(payments, List.of());

        List<OutboundNotification> obligations = capturedObligations(repository);
        assertThat(obligations).hasSize(2)
                .extracting(OutboundNotification::recipientIspb)
                .containsExactly("20000001", "20000001");
        JsonNode first = document(obligations.getFirst());
        JsonNode second = document(obligations.getLast());
        assertThat(first.at("/GrpHdr/NbOfTxs").asInt()).isEqualTo(15);
        assertThat(first.path("CdtTrfTxInf")).hasSize(15);
        assertThat(first.at("/CdtTrfTxInf/0/PmtId/EndToEndId").asText()).isEqualTo("E2E-1");
        assertThat(first.at("/CdtTrfTxInf/14/PmtId/EndToEndId").asText()).isEqualTo("E2E-15");
        assertThat(second.at("/GrpHdr/NbOfTxs").asInt()).isOne();
        assertThat(second.path("CdtTrfTxInf")).hasSize(1);
        assertThat(second.at("/CdtTrfTxInf/0/PmtId/EndToEndId").asText()).isEqualTo("E2E-16");
    }

    @Test
    void statusResultsForOneRecipientShareAPacs002WithMixedStatuses() throws Exception {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);
        PaymentTransactionCommand settled = payment("E2E-SETTLED", "10000001", "20000001");
        PaymentTransactionCommand rejected = payment("E2E-REJECTED", "10000001", "30000001");

        service.storeStatusObligations(
                List.of(new PaymentSettlement(PaymentReference.from(settled), List.of())),
                List.of(PaymentRejection.insufficientFunds(rejected))
        );

        List<OutboundNotification> obligations = capturedObligations(repository);
        assertThat(obligations).hasSize(2);
        OutboundNotification payerMessage = obligations.stream()
                .filter(notification -> notification.recipientIspb().equals("10000001"))
                .findFirst()
                .orElseThrow();
        JsonNode document = document(payerMessage);
        assertThat(document.at("/GrpHdr/NbOfTxs").asInt()).isEqualTo(2);
        assertThat(document.path("TxInfAndSts")).hasSize(2);
        assertThat(document.at("/TxInfAndSts/0/OrgnlEndToEndId").asText()).isEqualTo("E2E-SETTLED");
        assertThat(document.at("/TxInfAndSts/0/TxSts").asText()).isEqualTo("ACSC");
        assertThat(document.at("/TxInfAndSts/1/OrgnlEndToEndId").asText()).isEqualTo("E2E-REJECTED");
        assertThat(document.at("/TxInfAndSts/1/TxSts").asText()).isEqualTo("RJCT");
        assertThat(document.at("/TxInfAndSts/1/StsRsnInf/0/Rsn/Cd").asText()).isEqualTo("AM04");
    }

    @Test
    void communicationIdIsThePacsGroupHeaderMessageId() throws Exception {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);

        service.storeTransactionObligations(
                List.of(payment("E2E-1", "10000001", "20000001")),
                List.of()
        );

        OutboundNotification obligation = capturedObligations(repository).getFirst();
        assertThat(obligation.communicationId())
                .isEqualTo(document(obligation).at("/GrpHdr/MsgId").asText());
    }

    @Test
    void databaseResourceFailurePropagatesWithoutNotificationWrapper() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        NotificationObligationService service = service(repository);
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("database unavailable");
        doThrow(databaseFailure).when(repository).insertAll(anyList());

        assertThatThrownBy(() -> service.storeTransactionObligations(
                List.of(payment("E2E-1", "10000001", "20000001")),
                List.of()
        )).isSameAs(databaseFailure);
    }

    @Test
    void schedulesEveryStoredObligationForAfterCommitBestEffortDelivery() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NotificationObligationService service = service(repository, eventPublisher);

        service.storeTransactionObligations(
                List.of(
                        payment("E2E-REPLAY", "10000001", "20000001"),
                        payment("E2E-NEW", "10000002", "20000002")
                ),
                List.of()
        );

        ArgumentCaptor<OutboundNotificationBatchReady> event =
                ArgumentCaptor.forClass(OutboundNotificationBatchReady.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().notifications())
                .extracting(this::payload)
                .satisfiesExactly(
                        first -> assertThat(first).contains("E2E-REPLAY"),
                        second -> assertThat(second).contains("E2E-NEW")
                );
    }

    @Test
    void doesNotScheduleKafkaWhenTheOutboundInsertFails() {
        OutboundNotificationStore repository = mock(OutboundNotificationStore.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NotificationObligationService service = service(repository, eventPublisher);
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("database unavailable");
        doThrow(databaseFailure).when(repository).insertAll(anyList());

        assertThatThrownBy(() -> service.storeTransactionObligations(
                List.of(
                        payment("E2E-CONFLICT", "10000001", "20000001"),
                        payment("E2E-NEW", "10000002", "20000002")
                ),
                List.of()
        )).isSameAs(databaseFailure);

        verifyNoInteractions(eventPublisher);
    }

    private NotificationObligationService service(OutboundNotificationStore repository) {
        return service(repository, mock(ApplicationEventPublisher.class));
    }

    private NotificationObligationService service(
            OutboundNotificationStore repository,
            ApplicationEventPublisher eventPublisher
    ) {
        return new NotificationObligationService(
                new NotificationPayloadFactory(),
                new NotificationContentSerializer(new ObjectMapper().findAndRegisterModules()),
                repository,
                eventPublisher
        );
    }

    private List<OutboundNotification> capturedObligations(OutboundNotificationStore repository) {
        ArgumentCaptor<List<OutboundNotification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertAll(captor.capture());
        return captor.getValue();
    }

    private String payload(OutboundNotification notification) {
        return new String(notification.payload(), StandardCharsets.UTF_8);
    }

    private JsonNode document(OutboundNotification notification) throws Exception {
        return OBJECT_MAPPER.readTree(notification.payload());
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
