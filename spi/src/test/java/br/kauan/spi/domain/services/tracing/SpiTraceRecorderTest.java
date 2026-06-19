package br.kauan.spi.domain.services.tracing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpiTraceRecorderTest {

    @TempDir
    private Path tempDir;

    @Test
    void inactiveRecorderDoesNotCreateTraceFile() {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = new SpiTraceRecorder(traceFile.toString());

        recorder.record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);

        assertFalse(Files.exists(traceFile));
    }

    @Test
    void startedRecorderWritesHeaderAndEvents() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = new SpiTraceRecorder(traceFile.toString());

        recorder.start();
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_SAVED);

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals("timestamp_ns,end_to_end_id,event", lines.getFirst());
        assertEventLine(lines.get(1), "E2E-1", "request_consumed");
        assertEventLine(lines.get(2), "E2E-1", "request_saved");
    }

    @Test
    void stopDisablesRecording() throws Exception {
        Path traceFile = tempDir.resolve("spi-trace.csv");
        SpiTraceRecorder recorder = new SpiTraceRecorder(traceFile.toString());

        recorder.start();
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        recorder.stop();
        recorder.record("E2E-1", SpiTraceEvent.REQUEST_SAVED);

        List<String> lines = Files.readAllLines(traceFile);
        assertEquals(2, lines.size());
        assertEventLine(lines.get(1), "E2E-1", "request_consumed");
    }

    private static void assertEventLine(String line, String endToEndId, String event) {
        String[] columns = line.split(",");
        assertEquals(3, columns.length);
        Long.parseLong(columns[0]);
        assertEquals(endToEndId, columns[1]);
        assertEquals(event, columns[2]);
    }
}
