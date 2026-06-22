package br.kauan.spi.domain.services.tracing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiTraceRecorderTest {

    private static final int DEFAULT_QUEUE_CAPACITY = 8_192;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 250;
    private static final int DEFAULT_BATCH_SIZE = 1_024;
    private static final int FULL_TRACE_SAMPLE_RATE = 1;
    private static final String WRITER_THREAD_NAME = "spi-trace-writer";

    @TempDir
    private Path tempDir;

    @Test
    void inactiveRecorderDoesNotCreateTraceFile() {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = recorder(traceFile);

        recorder.record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);

        assertFalse(Files.exists(traceFile));
    }

    @Test
    void startedRecorderWritesHeaderAndEvents() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = recorder(traceFile);

        recorder.start();
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_SAVED);
        recorder.stop();

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals("timestamp_ns,end_to_end_id,event", lines.getFirst());
        assertEventLine(lines.get(1), "E2E-1", "request_consumed");
        assertEventLine(lines.get(2), "E2E-1", "request_saved");
    }

    @Test
    void stopDisablesRecording() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = recorder(traceFile);

        recorder.start();
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        recorder.stop();
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_SAVED);

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals(2, lines.size());
        assertEventLine(lines.get(1), "E2E-1", "request_consumed");
    }

    @Test
    void nullEndToEndIdIsIgnored() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = recorder(traceFile);

        recorder.start();
        recorder.record(null, SpiTraceEvent.REQUEST_CONSUMED);
        recorder.stop();

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals(List.of("timestamp_ns,end_to_end_id,event"), lines);
    }

    @Test
    void nullEventIsIgnored() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = recorder(traceFile);

        try {
            recorder.start();
            assertDoesNotThrow(() -> recorder.record("E2E-1", null));
        } finally {
            recorder.stop();
        }

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals(List.of("timestamp_ns,end_to_end_id,event"), lines);
    }

    @Test
    void writerThreadOnlyRunsWhileTraceIsActive() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = recorder(traceFile);

        assertFalse(hasWriterThread());

        recorder.start();
        assertTrue(waitUntil(SpiTraceRecorderTest::hasWriterThread));

        recorder.stop();
        assertTrue(waitUntil(() -> !hasWriterThread()));
    }

    @Test
    void fullQueueDropsTraceEventsWithoutThrowing() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = new SpiTraceRecorder(
                traceFile.toString(),
                1,
                60_000,
                1,
                FULL_TRACE_SAMPLE_RATE
        );

        recorder.start();
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10_000; i++) {
                recorder.record("E2E-" + i, SpiTraceEvent.REQUEST_CONSUMED);
            }
        });
        recorder.stop();

        List<String> lines = Files.readAllLines(traceFile);
        assertTrue(lines.size() >= 2);
        assertTrue(lines.size() < 10_001);
        assertEquals("timestamp_ns,end_to_end_id,event", lines.getFirst());
    }

    @Test
    void sampleRateKeepsAllEventsForSelectedPaymentIdsOnly() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = new SpiTraceRecorder(
                traceFile.toString(),
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_FLUSH_INTERVAL_MS,
                DEFAULT_BATCH_SIZE,
                2
        );

        recorder.start();
        recorder.record("A", SpiTraceEvent.REQUEST_CONSUMED);
        recorder.record("A", SpiTraceEvent.REQUEST_SAVED);
        recorder.record("B", SpiTraceEvent.REQUEST_CONSUMED);
        recorder.record("B", SpiTraceEvent.REQUEST_SAVED);
        recorder.stop();

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals(3, lines.size());
        assertEquals("timestamp_ns,end_to_end_id,event", lines.getFirst());
        assertEventLine(lines.get(1), "B", "request_consumed");
        assertEventLine(lines.get(2), "B", "request_saved");
    }

    @Test
    void stopDoesNotWaitIndefinitelyForWriterThread() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = new SpiTraceRecorder(
                traceFile.toString(),
                DEFAULT_QUEUE_CAPACITY,
                60_000,
                DEFAULT_BATCH_SIZE,
                FULL_TRACE_SAMPLE_RATE
        );

        recorder.start();
        Thread stopThread = new Thread(recorder::stop, "spi-trace-stop-test");
        stopThread.start();
        stopThread.join(6_000);

        try {
            assertFalse(stopThread.isAlive());
        } finally {
            if (stopThread.isAlive()) {
                stopThread.interrupt();
                stopThread.join(2_000);
            }
        }
    }

    private static SpiTraceRecorder recorder(Path traceFile) {
        return new SpiTraceRecorder(
                traceFile.toString(),
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_FLUSH_INTERVAL_MS,
                DEFAULT_BATCH_SIZE,
                FULL_TRACE_SAMPLE_RATE
        );
    }

    private static boolean hasWriterThread() {
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        return threads.stream()
                .anyMatch(thread -> thread.getName().equals(WRITER_THREAD_NAME) && thread.isAlive());
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static void assertEventLine(String line, String endToEndId, String event) {
        String[] columns = line.split(",");
        assertEquals(3, columns.length);
        Long.parseLong(columns[0]);
        assertEquals(endToEndId, columns[1]);
        assertEquals(event, columns[2]);
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
