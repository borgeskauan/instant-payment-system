package br.kauan.spi.adapter.input.kafka;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentMessageConsumerTest {

    @Test
    void consumeMessageParsesPaymentTransactionJsonOnlyOnce() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor
        );
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(PaymentBatch.builder().transactions(List.of()).build());

        consumer.consumeMessage("""
                {"CdtTrfTxInf":[]}
                """.getBytes(StandardCharsets.UTF_8));

        verify(objectMapper).readTree(any(byte[].class));
        verify(objectMapper).treeToValue(any(JsonNode.class), eq(FIToFICustomerCreditTransfer.class));
        verify(objectMapper, never()).readValue(anyString(), any(Class.class));
        verify(paymentTransactionMapper).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
        verify(processor).processTransactionBatch("00000000", PaymentBatch.builder().transactions(List.of()).build());
    }
}
