package br.kauan.spi.adapter.input.dtos.pacs;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class PacsDateTime {

    private PacsDateTime() {
    }

    static java.time.Instant toInstant(XMLGregorianCalendar timestamp) {
        int timezoneMinutes = timestamp.getTimezone();
        ZoneOffset offset = timezoneMinutes == DatatypeConstants.FIELD_UNDEFINED
                ? ZoneOffset.UTC
                : ZoneOffset.ofTotalSeconds(timezoneMinutes * 60);

        return OffsetDateTime.of(
                timestamp.getYear(),
                timestamp.getMonth(),
                timestamp.getDay(),
                timestamp.getHour(),
                timestamp.getMinute(),
                timestamp.getSecond(),
                fractionalNanos(timestamp),
                offset
        ).toInstant();
    }

    private static int fractionalNanos(XMLGregorianCalendar timestamp) {
        BigDecimal fractionalSecond = timestamp.getFractionalSecond();
        if (fractionalSecond == null) {
            return 0;
        }
        return fractionalSecond.movePointRight(9).intValue();
    }
}
