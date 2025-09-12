package br.kauan.paymentserviceprovider.adapter.output.pacs.commons;

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

    public GroupHeader createGroupHeader(BatchDetails batchDetails) {
        var xmlTimestamp = convertInstantToXmlGregorianCalendar(batchDetails.getCreatedAt());

        return GroupHeader.builder()
                .messageId(batchDetails.getId())
                .creationTimestamp(xmlTimestamp)
                .numberOfTransactions(BigInteger.valueOf(batchDetails.getTotalTransactions()))
                .build();
    }

    private static XMLGregorianCalendar convertInstantToXmlGregorianCalendar(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);

        // Create a GregorianCalendar from the ZonedDateTime
        GregorianCalendar gregorianCalendar = GregorianCalendar.from(zdt);

        // Create the XMLGregorianCalendar
        try {
            return DatatypeFactory.newInstance()
                    .newXMLGregorianCalendar(gregorianCalendar);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
