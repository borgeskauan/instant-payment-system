package br.kauan.spi;

import br.kauan.spi.adapter.output.outbox.NotificationOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class SpiApplicationTests {

    @MockitoBean
    private NotificationOutboxWorker notificationOutboxWorker;

    @Test
    void contextLoads() {
    }

}
