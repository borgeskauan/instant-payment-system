package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryRepositoryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(NotificationDeliveryRepository.class);

    @Test
    void repositoryBeanUsesAutowiredConstructor() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(NotificationDeliveryRepository.class));
    }

    @Test
    void retryableFailureDoesNotRewriteAckedDeliveries() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        NotificationDeliveryRepository repository = new NotificationDeliveryRepository(
                jdbcTemplate,
                mock(TransactionTemplate.class)
        );
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        repository.markRetryableFailed("v1:abc", "send failed", Duration.ofSeconds(1));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).contains("delivery_status <> 'ACKED'");
    }
}
