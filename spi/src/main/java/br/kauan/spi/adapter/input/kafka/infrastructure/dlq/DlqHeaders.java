package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

final class DlqHeaders {

    private static final int STACKTRACE_LIMIT = 4096;
    private static final Clock CLOCK = Clock.systemUTC();

    private DlqHeaders() {
    }

    static Headers from(
            ConsumerRecord<?, ?> sourceRecord,
            String consumerGroup,
            String errorType,
            Exception exception
    ) {
        Headers headers = new RecordHeaders();
        add(headers, "dlq.source-topic", sourceRecord.topic());
        add(headers, "dlq.source-partition", Integer.toString(sourceRecord.partition()));
        add(headers, "dlq.source-offset", Long.toString(sourceRecord.offset()));
        add(headers, "dlq.source-timestamp", Long.toString(sourceRecord.timestamp()));
        add(headers, "dlq.consumer-group", consumerGroup);
        add(headers, "dlq.service", "spi");
        add(headers, "dlq.error-type", errorType);
        add(headers, "dlq.exception-class", exception.getClass().getName());
        add(headers, "dlq.error-message", errorMessage(exception));
        add(headers, "dlq.stacktrace-short", stacktrace(exception));
        add(headers, "dlq.failed-at", Instant.now(CLOCK).toString());
        return headers;
    }

    private static void add(Headers headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String errorMessage(Exception exception) {
        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    private static String stacktrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        String stacktrace = writer.toString();
        if (stacktrace.length() <= STACKTRACE_LIMIT) {
            return stacktrace;
        }
        return stacktrace.substring(0, STACKTRACE_LIMIT);
    }
}
