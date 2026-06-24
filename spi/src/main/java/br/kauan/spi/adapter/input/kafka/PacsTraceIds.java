package br.kauan.spi.adapter.input.kafka;

import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;

import java.nio.charset.StandardCharsets;

final class PacsTraceIds {

    private static final byte[] PAYMENT_ID_FIELD = "\"EndToEndId\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_ID_FIELD = "\"OrgnlEndToEndId\"".getBytes(StandardCharsets.US_ASCII);

    private PacsTraceIds() {
    }

    static void recordPaymentRequestReceived(byte[] payload, SpiTraceRecorder traceRecorder) {
        recordIds(payload, PAYMENT_ID_FIELD, traceRecorder, SpiTraceEvent.REQUEST_RECEIVED);
    }

    static void recordStatusReportReceived(byte[] payload, SpiTraceRecorder traceRecorder) {
        recordIds(payload, STATUS_ID_FIELD, traceRecorder, SpiTraceEvent.STATUS_RECEIVED);
    }

    private static void recordIds(
            byte[] payload,
            byte[] fieldName,
            SpiTraceRecorder traceRecorder,
            SpiTraceEvent event
    ) {
        if (!traceRecorder.isActive()) {
            return;
        }

        int searchFrom = 0;
        while (searchFrom < payload.length) {
            int fieldStart = indexOf(payload, fieldName, searchFrom);
            if (fieldStart < 0) {
                return;
            }

            int valueStart = valueStart(payload, fieldStart + fieldName.length);
            if (valueStart < 0) {
                searchFrom = fieldStart + fieldName.length;
                continue;
            }

            int valueEnd = stringEnd(payload, valueStart);
            if (valueEnd < 0) {
                return;
            }

            if (valueEnd > valueStart) {
                traceRecorder.record(new String(payload, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8), event);
            }
            searchFrom = valueEnd + 1;
        }
    }

    private static int valueStart(byte[] payload, int index) {
        int colon = skipWhitespace(payload, index);
        if (colon >= payload.length || payload[colon] != ':') {
            return -1;
        }

        int quote = skipWhitespace(payload, colon + 1);
        if (quote >= payload.length || payload[quote] != '"') {
            return -1;
        }

        return quote + 1;
    }

    private static int stringEnd(byte[] payload, int index) {
        boolean escaped = false;
        for (int i = index; i < payload.length; i++) {
            byte current = payload[i];
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(byte[] payload, int index) {
        int current = index;
        while (current < payload.length) {
            byte value = payload[current];
            if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
                break;
            }
            current++;
        }
        return current;
    }

    private static int indexOf(byte[] payload, byte[] target, int fromIndex) {
        int lastStart = payload.length - target.length;
        for (int i = fromIndex; i <= lastStart; i++) {
            if (matchesAt(payload, target, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesAt(byte[] payload, byte[] target, int offset) {
        for (int i = 0; i < target.length; i++) {
            if (payload[offset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }
}
