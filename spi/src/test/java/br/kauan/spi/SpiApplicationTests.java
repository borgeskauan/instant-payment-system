package br.kauan.spi;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class SpiApplicationTests {

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Test
    void contextLoads() {
    }

}
