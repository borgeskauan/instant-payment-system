package br.kauan.spi.port.output;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFundsRepository implements FundsRepository {

    private static final String INSERT_OR_KEEP_BALANCE_SQL = """
            INSERT INTO participant_balance_entity (bank_code, balance_cents)
            VALUES (?, ?)
            ON CONFLICT (bank_code) DO NOTHING
            """;

    private static final String INSERT_OR_RESET_BALANCE_SQL = """
            INSERT INTO participant_balance_entity (bank_code, balance_cents)
            VALUES (?, ?)
            ON CONFLICT (bank_code) DO UPDATE
            SET balance_cents = EXCLUDED.balance_cents
            """;

    private static final String SELECT_BALANCE_SQL = """
            SELECT balance_cents
            FROM participant_balance_entity
            WHERE bank_code = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcFundsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void provisionAccount(String bankCode, long balanceCents, boolean resetIfExists) {
        String sql = resetIfExists ? INSERT_OR_RESET_BALANCE_SQL : INSERT_OR_KEEP_BALANCE_SQL;
        jdbcTemplate.update(sql, bankCode, balanceCents);
    }

    @Override
    public long getAvailableFundsCents(String bankCode) {
        try {
            Long balance = jdbcTemplate.queryForObject(SELECT_BALANCE_SQL, Long.class, bankCode);
            if (balance == null) {
                throw accountNotFound();
            }
            return balance;
        } catch (EmptyResultDataAccessException exception) {
            throw accountNotFound();
        }
    }

    private IllegalStateException accountNotFound() {
        return new IllegalStateException("Settlement account not found");
    }
}
