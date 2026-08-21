package br.kauan.spi.port.output;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ParticipantBalanceSchemaIntegrationTest {

    private static final String ISPB = "10000001";

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private FundsRepository fundsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM participant_balance_entity WHERE bank_code = ?", ISPB);
    }

    @Test
    void migrationLeavesOnlyTheParticipantBalanceTable() {
        assertThat(regclass("participant_balance_entity")).isEqualTo("participant_balance_entity");
        assertThat(regclass("funds_bucket_entity")).isNull();
        assertThat(regclass("funds_entity")).isNull();
    }

    @Test
    void provisionPreservesOrResetsExactlyOneParticipantRow() {
        fundsRepository.provisionAccount(ISPB, 1_000L, false);
        fundsRepository.provisionAccount(ISPB, 2_000L, false);

        assertThat(fundsRepository.getAvailableFundsCents(ISPB)).isEqualTo(1_000L);
        assertThat(rowCount()).isOne();

        fundsRepository.provisionAccount(ISPB, 2_000L, true);

        assertThat(fundsRepository.getAvailableFundsCents(ISPB)).isEqualTo(2_000L);
        assertThat(rowCount()).isOne();
    }

    @Test
    void participantBalanceCannotBecomeNegative() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO participant_balance_entity (bank_code, balance_cents) VALUES (?, ?)",
                ISPB,
                -1L
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private String regclass(String tableName) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?)::text", String.class, "public." + tableName);
    }

    private int rowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM participant_balance_entity WHERE bank_code = ?",
                Integer.class,
                ISPB
        );
        return count == null ? 0 : count;
    }
}
