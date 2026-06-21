package br.kauan.spi.domain.services.tracing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SpiTraceRecorder {

    private static final String HEADER = "timestamp_ns,end_to_end_id,event";

    private final Path traceFile;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Object lock = new Object();
    private BufferedWriter writer;

    public SpiTraceRecorder(
            @Value("${spi.trace.file:/tmp/spi-trace.csv}") String traceFile
    ) {
        this.traceFile = Path.of(traceFile);
    }

    public void start() {
        synchronized (lock) {
            try {
                closeWriter();
                Path parent = traceFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.deleteIfExists(traceFile);
                active.set(true);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start SPI trace recording", e);
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            try {
                active.set(false);
                closeWriter();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to stop SPI trace recording", e);
            }
        }
    }

    public void record(String endToEndId, SpiTraceEvent event) {
        if (!active.get() || endToEndId == null) {
            return;
        }

        synchronized (lock) {
            try {
                writer().write(epochNanos() + "," + endToEndId + "," + event.eventName());
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write SPI trace event", e);
            }
        }
    }

    private BufferedWriter writer() throws IOException {
        if (writer == null) {
            Path parent = traceFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean writeHeader = Files.notExists(traceFile) || Files.size(traceFile) == 0;
            writer = Files.newBufferedWriter(
                    traceFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            if (writeHeader) {
                writer.write(HEADER);
                writer.newLine();
            }
        }
        return writer;
    }

    private void closeWriter() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private static long epochNanos() {
        Instant now = Instant.now();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
    }
}
