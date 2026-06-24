package br.kauan.spi.adapter.input.dtos.pacs;

import br.kauan.spi.adapter.input.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.ExternalPaymentTransactionStatusCode;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.PaymentTransactionInfo;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.junit.jupiter.api.Test;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusReportMapperTest {

    @Test
    void fromRegulatoryReportParsesCreationTimestampWithoutGregorianCalendarAllocation() {
        XMLGregorianCalendar timestamp = mock(XMLGregorianCalendar.class);
        stubTimestamp(timestamp);
        when(timestamp.toGregorianCalendar())
                .thenThrow(new AssertionError("toGregorianCalendar should not be used on the hot path"));
        StatusReportMapper mapper = new StatusReportMapper(mock(CommonsMapper.class), new CodeMapping());

        var batch = mapper.fromRegulatoryReport(FIToFIPaymentStatusReport.builder()
                .groupHeader(GroupHeader.builder()
                        .messageId("status-batch-1")
                        .creationTimestamp(timestamp)
                        .build())
                .transactionInfo(List.of(PaymentTransactionInfo.builder()
                        .originalPaymentId("E2E-1")
                        .status(ExternalPaymentTransactionStatusCode.ACCC)
                        .build()))
                .build());

        assertThat(batch.getBatchDetails().getCreatedAt())
                .isEqualTo(Instant.parse("2026-06-23T20:00:01.123Z"));
        assertThat(batch.getStatusReports()).hasSize(1);
        assertThat(batch.getStatusReports().getFirst().getStatus())
                .isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
    }

    private static void stubTimestamp(XMLGregorianCalendar timestamp) {
        when(timestamp.getYear()).thenReturn(2026);
        when(timestamp.getMonth()).thenReturn(6);
        when(timestamp.getDay()).thenReturn(23);
        when(timestamp.getHour()).thenReturn(20);
        when(timestamp.getMinute()).thenReturn(0);
        when(timestamp.getSecond()).thenReturn(1);
        when(timestamp.getFractionalSecond()).thenReturn(new BigDecimal("0.123"));
        when(timestamp.getTimezone()).thenReturn(0);
    }
}
