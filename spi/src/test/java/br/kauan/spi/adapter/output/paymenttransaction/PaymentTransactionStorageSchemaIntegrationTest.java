package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentTransactionStorageSchemaIntegrationTest {

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationReservesHalfOfPaymentPagesForHotUpdates() {
        String relationOptions = jdbcTemplate.queryForObject(
                """
                        SELECT COALESCE(array_to_string(reloptions, ','), '')
                        FROM pg_class
                        WHERE oid = 'payment_transaction_entity'::regclass
                        """,
                String.class
        );

        assertThat(relationOptions).contains("fillfactor=50");
    }
}
