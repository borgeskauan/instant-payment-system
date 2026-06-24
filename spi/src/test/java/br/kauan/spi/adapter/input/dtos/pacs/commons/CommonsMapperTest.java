package br.kauan.spi.adapter.input.dtos.pacs.commons;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CommonsMapperTest {

    @Test
    void createGroupHeaderUsesOffsetDateTimeTimestamp() {
        CommonsMapper mapper = new CommonsMapper();

        GroupHeader header = mapper.createGroupHeader(batchDetails("batch-1"));

        assertThat(header.getCreationTimestamp())
                .isEqualTo(OffsetDateTime.parse("2026-06-13T20:00:00Z"));
    }

    private static BatchDetails batchDetails(String id) {
        return BatchDetails.builder()
                .id(id)
                .createdAt(Instant.parse("2026-06-13T20:00:00Z"))
                .totalTransactions(1)
                .build();
    }
}
