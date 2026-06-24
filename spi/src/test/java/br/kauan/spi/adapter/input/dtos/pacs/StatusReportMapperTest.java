package br.kauan.spi.adapter.input.dtos.pacs;

import br.kauan.spi.adapter.input.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.ExternalPaymentTransactionStatusCode;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.PaymentTransactionInfo;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StatusReportMapperTest {

    @Test
    void fromRegulatoryReportUsesOffsetDateTimeCreationTimestamp() {
        StatusReportMapper mapper = new StatusReportMapper(mock(CommonsMapper.class), new CodeMapping());

        var batch = mapper.fromRegulatoryReport(FIToFIPaymentStatusReport.builder()
                .groupHeader(GroupHeader.builder()
                        .messageId("status-batch-1")
                        .creationTimestamp(OffsetDateTime.parse("2026-06-23T20:00:01.123Z"))
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

}
