package br.kauan.paymentserviceprovider.adapter.input.notification;

import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.services.cts.IncomingTransactionService;
import br.kauan.paymentserviceprovider.domain.services.cts.StatusProcessingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationProcessorTest {

    private static final String BANK_CODE = "12345678";

    private PaymentTransactionMapper paymentTransactionMapper;
    private StatusReportMapper statusReportMapper;
    private StatusProcessingService statusProcessingService;
    private IncomingTransactionService incomingTransactionService;
    private NotificationProcessor processor;

    @BeforeEach
    void setUp() {
        new GlobalVariables().setBankCode(BANK_CODE);
        paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        statusReportMapper = mock(StatusReportMapper.class);
        statusProcessingService = mock(StatusProcessingService.class);
        incomingTransactionService = mock(IncomingTransactionService.class);
        processor = new NotificationProcessor(
                paymentTransactionMapper,
                statusReportMapper,
                statusProcessingService,
                incomingTransactionService,
                new ObjectMapper()
        );
    }

    @Test
    void pacs008NotificationIsMappedAndSentToIncomingTransactionService() {
        PaymentTransaction transaction = PaymentTransaction.builder().paymentId("E2E-1").build();
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(List.of(transaction));

        processor.process(BANK_CODE, "{\"CdtTrfTxInf\":[]}");

        verify(incomingTransactionService).handleTransferRequests(List.of(transaction));
        verifyNoInteractions(statusProcessingService);
    }

    @Test
    void pacs002NotificationIsMappedAndSentToStatusProcessingService() {
        StatusReport statusReport = StatusReport.builder().originalPaymentId("E2E-1").build();
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(List.of(statusReport));

        processor.process(BANK_CODE, "{\"TxInfAndSts\":[]}");

        verify(statusProcessingService).handleStatuses(List.of(statusReport));
        verifyNoInteractions(incomingTransactionService);
    }

    @Test
    void notificationForAnotherIspbIsIgnored() {
        processor.process("87654321", "{\"CdtTrfTxInf\":[]}");

        verifyNoInteractions(
                paymentTransactionMapper,
                statusReportMapper,
                statusProcessingService,
                incomingTransactionService
        );
    }

    @Test
    void unknownPayloadIsIgnored() {
        processor.process(BANK_CODE, "{\"Other\":[]}");

        verifyNoInteractions(
                paymentTransactionMapper,
                statusReportMapper,
                statusProcessingService,
                incomingTransactionService
        );
    }

    @Test
    void invalidJsonDoesNotPropagateAnException() {
        processor.process(BANK_CODE, "{");

        verifyNoInteractions(
                paymentTransactionMapper,
                statusReportMapper,
                statusProcessingService,
                incomingTransactionService
        );
    }
}
