package br.kauan.spi.domain.services.tracing;

import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SpiTraceRecorder {

    private static final String HEADER = "timestamp_ns,end_to_end_id,event";
    private static final String WRITER_THREAD_NAME = "spi-trace-writer";
    private static final long STOP_TIMEOUT_MS = 5_000;

    private final Path traceFile;
    private final int queueCapacity;
    private final int flushIntervalMs;
    private final int batchSize;
    private final int sampleRate;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong droppedEvents = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private volatile BlockingQueue<TraceRecord> queue;
    private volatile TraceWriter writer;
    private volatile Thread writerThread;

    public SpiTraceRecorder(
            @Value("${spi.trace.file:/tmp/spi-trace.csv}") String traceFile,
            @Value("${spi.trace.queue-capacity:8192}") int queueCapacity,
            @Value("${spi.trace.flush-interval-ms:250}") int flushIntervalMs,
            @Value("${spi.trace.batch-size:1024}") int batchSize,
            @Value("${spi.trace.sample-rate:100}") int sampleRate
    ) {
        this.traceFile = Path.of(traceFile);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.flushIntervalMs = Math.max(1, flushIntervalMs);
        this.batchSize = Math.max(1, batchSize);
        this.sampleRate = Math.max(1, sampleRate);
    }

    public void start() {
        synchronized (lifecycleLock) {
            try {
                stopWriter();
                Path parent = traceFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.deleteIfExists(traceFile);
                BufferedWriter traceWriter = Files.newBufferedWriter(
                        traceFile,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
                traceWriter.write(HEADER);
                traceWriter.newLine();
                traceWriter.flush();

                BlockingQueue<TraceRecord> nextQueue = new ArrayBlockingQueue<>(queueCapacity);
                TraceWriter nextWriter = new TraceWriter(nextQueue, traceWriter, flushIntervalMs, batchSize);
                Thread nextWriterThread = new Thread(nextWriter, WRITER_THREAD_NAME);
                nextWriterThread.setDaemon(true);

                droppedEvents.set(0);
                queue = nextQueue;
                writer = nextWriter;
                writerThread = nextWriterThread;
                nextWriterThread.start();
                active.set(true);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start SPI trace recording", e);
            }
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopWriter();
        }
    }

    public void record(String endToEndId, SpiTraceEvent event) {
        if (!active.get() || endToEndId == null || event == null) {
            return;
        }
        if (!shouldTrace(endToEndId)) {
            return;
        }

        BlockingQueue<TraceRecord> currentQueue = queue;
        TraceWriter currentWriter = writer;
        Thread currentWriterThread = writerThread;
        if (
                currentQueue == null
                        || currentWriter == null
                        || currentWriter.failure() != null
                        || currentWriterThread == null
                        || !currentWriterThread.isAlive()
        ) {
            active.set(false);
            return;
        }

        boolean accepted = currentQueue.offer(new TraceRecord(epochNanos(), endToEndId, event.eventName()));
        if (!accepted) {
            long dropped = droppedEvents.incrementAndGet();
            if (dropped == 1 || dropped % 10_000 == 0) {
                log.warn("Dropped {} SPI trace events because the trace queue is full", dropped);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private void stopWriter() {
        active.set(false);

        TraceWriter currentWriter = writer;
        Thread currentWriterThread = writerThread;
        if (currentWriter == null || currentWriterThread == null) {
            clearWriterState();
            return;
        }

        currentWriter.stop();
        try {
            currentWriterThread.join(STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (currentWriterThread.isAlive()) {
            log.warn("SPI trace writer did not stop within timeout");
            currentWriterThread.interrupt();
        }

        IOException failure = currentWriter.failure();
        long dropped = droppedEvents.get();
        clearWriterState();

        if (dropped > 0) {
            log.warn("Dropped {} SPI trace events because the trace queue was full", dropped);
        }

        if (failure != null) {
            log.warn("SPI trace writer stopped with an I/O failure", failure);
        }
    }

    private void clearWriterState() {
        queue = null;
        writer = null;
        writerThread = null;
    }

    private boolean shouldTrace(String endToEndId) {
        return sampleRate == 1 || Math.floorMod(endToEndId.hashCode(), sampleRate) == 0;
    }

    private static long epochNanos() {
        Instant now = Instant.now();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
    }

    private record TraceRecord(long timestampNs, String endToEndId, String eventName) {

        private String toCsvLine() {
            return timestampNs + "," + endToEndId + "," + eventName;
        }
    }

    private static final class TraceWriter implements Runnable {

        private final BlockingQueue<TraceRecord> queue;
        private final BufferedWriter writer;
        private final int flushIntervalMs;
        private final int batchSize;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile IOException failure;

        private TraceWriter(
                BlockingQueue<TraceRecord> queue,
                BufferedWriter writer,
                int flushIntervalMs,
                int batchSize
        ) {
            this.queue = queue;
            this.writer = writer;
            this.flushIntervalMs = flushIntervalMs;
            this.batchSize = batchSize;
        }

        @Override
        public void run() {
            List<TraceRecord> batch = new ArrayList<>(batchSize);
            try {
                while (running.get() || !queue.isEmpty()) {
                    TraceRecord first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        batch.add(first);
                        queue.drainTo(batch, batchSize - 1);
                        writeBatch(batch);
                        batch.clear();
                    }
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                failure = e;
            } finally {
                try {
                    if (!batch.isEmpty()) {
                        writeBatch(batch);
                    }
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
        }

        private void stop() {
            running.set(false);
        }

        private IOException failure() {
            return failure;
        }

        private void writeBatch(List<TraceRecord> batch) throws IOException {
            for (TraceRecord record : batch) {
                writer.write(record.toCsvLine());
                writer.newLine();
            }
        }
    }
}
