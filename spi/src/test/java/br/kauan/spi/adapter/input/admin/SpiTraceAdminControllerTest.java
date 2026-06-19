package br.kauan.spi.adapter.input.admin;

import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpiTraceAdminControllerTest {

    @Test
    void startTraceDelegatesToRecorder() {
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        SpiTraceAdminController controller = new SpiTraceAdminController(traceRecorder);

        var response = controller.startTrace();

        assertEquals(204, response.getStatusCode().value());
        verify(traceRecorder).start();
    }

    @Test
    void stopTraceDelegatesToRecorder() {
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        SpiTraceAdminController controller = new SpiTraceAdminController(traceRecorder);

        var response = controller.stopTrace();

        assertEquals(204, response.getStatusCode().value());
        verify(traceRecorder).stop();
    }
}
