package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SpiSchemaBaselineIntegrationTest {

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cleanDatabaseUsesOneSupportedBaselineWithoutLegacyRelations() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spi_flyway_schema_history WHERE success",
                Integer.class
        );

        assertThat(appliedMigrations).isEqualTo(1);
        assertThat(regclass("payment_audit_event_legacy_v16")).isNull();
        assertThat(regclass("payment_audit_event_history")).isNull();
    }

    private String regclass(String relationName) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, relationName);
    }
}
