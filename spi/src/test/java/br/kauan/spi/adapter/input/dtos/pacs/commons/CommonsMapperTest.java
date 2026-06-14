package br.kauan.spi.adapter.input.dtos.pacs.commons;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import org.junit.jupiter.api.Test;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.util.GregorianCalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonsMapperTest {

    @Test
    void createGroupHeaderReusesTheSameDatatypeFactory() {
        DatatypeFactory datatypeFactory = mock(DatatypeFactory.class);
        XMLGregorianCalendar xmlCalendar = mock(XMLGregorianCalendar.class);
        when(datatypeFactory.newXMLGregorianCalendar(any(GregorianCalendar.class)))
                .thenReturn(xmlCalendar);

        CommonsMapper mapper = new CommonsMapper(datatypeFactory);
        BatchDetails firstBatch = batchDetails("batch-1");
        BatchDetails secondBatch = batchDetails("batch-2");

        GroupHeader firstHeader = mapper.createGroupHeader(firstBatch);
        GroupHeader secondHeader = mapper.createGroupHeader(secondBatch);

        assertThat(firstHeader.getCreationTimestamp()).isSameAs(xmlCalendar);
        assertThat(secondHeader.getCreationTimestamp()).isSameAs(xmlCalendar);
        verify(datatypeFactory, times(2)).newXMLGregorianCalendar(any(GregorianCalendar.class));
    }

    private static BatchDetails batchDetails(String id) {
        return BatchDetails.builder()
                .id(id)
                .createdAt(Instant.parse("2026-06-13T20:00:00Z"))
                .totalTransactions(1)
                .build();
    }
}
