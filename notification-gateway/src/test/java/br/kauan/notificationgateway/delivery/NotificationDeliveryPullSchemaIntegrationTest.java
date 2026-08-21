package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///notification_gateway_pull_schema_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.table=notification_gateway_flyway_schema_history",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
class NotificationDeliveryPullSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schemaContainsOnlyTheMinimalDeliveryIndex() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'delivery_index'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "communication_id",
                "recipient_ispb",
                "delivery_position"
        );

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'delivery_index'
                ORDER BY indexname
                """, String.class);

        assertThat(indexes).containsExactly(
                "delivery_index_pkey",
                "delivery_index_recipient_position_key"
        );

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'notification_delivery'
                """, Integer.class)).isZero();
    }
}
