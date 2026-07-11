package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.StatusReportMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.IncomingPaymentRequestClassification;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncomingTransactionServiceTest {

    private static final String BANK_CODE = "20000001";

    private PaymentRepository paymentRepository;
    private StatusReportMapper statusReportMapper;
    private CentralTransferSystemRestClient transferRestClient;
    private IncomingTransactionService service;

    @BeforeEach
    void setUp() {
        new GlobalVariables().setBankCode(BANK_CODE);
        paymentRepository = mock(PaymentRepository.class);
        statusReportMapper = mock(StatusReportMapper.class);
        transferRestClient = mock(CentralTransferSystemRestClient.class);
        service = new IncomingTransactionService(
                paymentRepository,
                statusReportMapper,
                transferRestClient,
                new ObjectMapper()
        );
    }

    @Test
    void emitsAcceptedInProcessOnlyForClassifiedAcceptedRequests() {
        PaymentTransaction accepted = PaymentTransaction.builder().paymentId("E2E-1").build();
        PaymentTransaction divergent = PaymentTransaction.builder().paymentId("E2E-2").build();
        when(paymentRepository.storeAndClassifyIncomingRequests(List.of(accepted, divergent)))
                .thenReturn(new IncomingPaymentRequestClassification(List.of(accepted), List.of(divergent)));
        when(statusReportMapper.toRegulatoryReport(anyList()))
                .thenReturn(FIToFIPaymentStatusReport.builder().build());

        service.handleTransferRequests(List.of(accepted, divergent));

        ArgumentCaptor<List<StatusReport>> statuses = ArgumentCaptor.captor();
        verify(statusReportMapper).toRegulatoryReport(statuses.capture());
        assertThat(statuses.getValue())
                .extracting(StatusReport::getOriginalPaymentId, StatusReport::getStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS));
        verify(transferRestClient).sendTransferStatus(anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void doesNotSendStatusReportWhenEveryIncomingRequestIsDivergent() {
        PaymentTransaction divergent = PaymentTransaction.builder().paymentId("E2E-1").build();
        when(paymentRepository.storeAndClassifyIncomingRequests(List.of(divergent)))
                .thenReturn(new IncomingPaymentRequestClassification(List.of(), List.of(divergent)));

        service.handleTransferRequests(List.of(divergent));

        verify(statusReportMapper, never()).toRegulatoryReport(anyList());
        verify(transferRestClient, never()).sendTransferStatus(anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
    }
}
