package br.kauan.spi.adapter.input.admin;

import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/spi-trace")
public class SpiTraceAdminController {

    private final SpiTraceRecorder traceRecorder;

    public SpiTraceAdminController(SpiTraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @PostMapping("/start")
    public ResponseEntity<Void> startTrace() {
        traceRecorder.start();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stopTrace() {
        traceRecorder.stop();
        return ResponseEntity.noContent().build();
    }
}
