package br.kauan.paymentserviceprovider.adapter.input.notification;

import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.services.cts.IncomingTransactionService;
import br.kauan.paymentserviceprovider.domain.services.cts.PaymentOutcomeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationProcessorTest {

    private PaymentTransactionMapper paymentTransactionMapper;
    private StatusReportMapper statusReportMapper;
    private PaymentOutcomeService paymentOutcomeService;
    private IncomingTransactionService incomingTransactionService;
    private NotificationProcessor processor;

    @BeforeEach
    void setUp() {
        paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        statusReportMapper = mock(StatusReportMapper.class);
        paymentOutcomeService = mock(PaymentOutcomeService.class);
        incomingTransactionService = mock(IncomingTransactionService.class);
        processor = new NotificationProcessor(
                paymentTransactionMapper,
                statusReportMapper,
                paymentOutcomeService,
                incomingTransactionService,
                new ObjectMapper()
        );
    }

    @Test
    void pacs008NotificationIsMappedAndSentToIncomingTransactionService() {
        PaymentTransaction transaction = PaymentTransaction.builder().paymentId("E2E-1").build();
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(List.of(transaction));

        processor.process("{\"CdtTrfTxInf\":[]}");

        verify(incomingTransactionService).handleTransferRequests(List.of(transaction));
        verifyNoInteractions(paymentOutcomeService);
    }

    @Test
    void pacs002NotificationIsMappedAndSentToPaymentOutcomeService() {
        StatusReport statusReport = StatusReport.builder().originalPaymentId("E2E-1").build();
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(List.of(statusReport));

        processor.process("{\"TxInfAndSts\":[]}");

        verify(paymentOutcomeService).handleStatuses(List.of(statusReport));
        verifyNoInteractions(incomingTransactionService);
    }

    @Test
    void unknownPayloadPropagatesProcessingFailure() {
        assertThatThrownBy(() -> processor.process("{\"Other\":[]}"))
                .isInstanceOf(NotificationProcessingException.class);

        verifyNoInteractions(
                paymentTransactionMapper,
                statusReportMapper,
                paymentOutcomeService,
                incomingTransactionService
        );
    }

    @Test
    void invalidJsonPropagatesProcessingFailure() {
        assertThatThrownBy(() -> processor.process("{"))
                .isInstanceOf(NotificationProcessingException.class);

        verifyNoInteractions(
                paymentTransactionMapper,
                statusReportMapper,
                paymentOutcomeService,
                incomingTransactionService
        );
    }
}
