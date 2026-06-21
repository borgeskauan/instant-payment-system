package br.kauan.spi.adapter.input.dtos.pacs.commons;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;

@Service
public class CommonsMapper {

    private final DatatypeFactory datatypeFactory;

    public CommonsMapper() {
        this(createDatatypeFactory());
    }

    CommonsMapper(DatatypeFactory datatypeFactory) {
        this.datatypeFactory = datatypeFactory;
    }

    public GroupHeader createGroupHeader(BatchDetails batchDetails) {
        var xmlTimestamp = convertInstantToXmlGregorianCalendar(batchDetails.getCreatedAt());

        return GroupHeader.builder()
                .messageId(batchDetails.getId())
                .creationTimestamp(xmlTimestamp)
                .numberOfTransactions(BigInteger.valueOf(batchDetails.getTotalTransactions()))
                .build();
    }

    private XMLGregorianCalendar convertInstantToXmlGregorianCalendar(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);

        GregorianCalendar gregorianCalendar = GregorianCalendar.from(zdt);

        return datatypeFactory.newXMLGregorianCalendar(gregorianCalendar);
    }

    private static DatatypeFactory createDatatypeFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
