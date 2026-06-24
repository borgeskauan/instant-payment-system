package br.kauan.spi.adapter.input.dtos.pacs.commons;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.ZoneOffset;

@Service
public class CommonsMapper {

    public GroupHeader createGroupHeader(BatchDetails batchDetails) {
        return GroupHeader.builder()
                .messageId(batchDetails.getId())
                .creationTimestamp(batchDetails.getCreatedAt().atOffset(ZoneOffset.UTC))
                .numberOfTransactions(BigInteger.valueOf(batchDetails.getTotalTransactions()))
                .build();
    }
}
