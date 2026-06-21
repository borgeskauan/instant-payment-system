package br.kauan.spi.port.output;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Repository
public class FundsJpaAdapter implements FundsRepository {

    private static final int BUCKET_COUNT = 16;

    private static final String COUNT_BUCKETS_SQL = """
            SELECT COUNT(*)
            FROM funds_bucket_entity
            WHERE bank_code = ?
            """;

    private static final String INSERT_OR_KEEP_BUCKET_SQL = """
            INSERT INTO funds_bucket_entity (bank_code, bucket_id, balance)
            VALUES (?, ?, ?)
            ON CONFLICT (bank_code, bucket_id) DO NOTHING
            """;

    private static final String INSERT_OR_RESET_BUCKET_SQL = """
            INSERT INTO funds_bucket_entity (bank_code, bucket_id, balance)
            VALUES (?, ?, ?)
            ON CONFLICT (bank_code, bucket_id) DO UPDATE
            SET balance = EXCLUDED.balance
            """;

    private static final String SUM_BUCKETS_SQL = """
            SELECT COALESCE(SUM(balance), 0)
            FROM funds_bucket_entity
            WHERE bank_code = ?
            """;

    private static final String DEDUCT_BUCKET_SQL = """
            UPDATE funds_bucket_entity
            SET balance = balance - ?
            WHERE bank_code = ?
              AND bucket_id = 0
              AND balance >= ?
            """;

    private static final String ADD_BUCKET_SQL = """
            UPDATE funds_bucket_entity
            SET balance = balance + ?
            WHERE bank_code = ?
              AND bucket_id = 0
            """;

    private final JdbcTemplate jdbcTemplate;

    public FundsJpaAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void provisionAccount(String bankCode, BigDecimal balance, boolean resetIfExists) {
        BigDecimal bucketBalance = balance.divide(BigDecimal.valueOf(BUCKET_COUNT), 2, RoundingMode.DOWN);
        BigDecimal remainder = balance.subtract(bucketBalance.multiply(BigDecimal.valueOf(BUCKET_COUNT)));
        String sql = resetIfExists ? INSERT_OR_RESET_BUCKET_SQL : INSERT_OR_KEEP_BUCKET_SQL;

        for (int bucketId = 0; bucketId < BUCKET_COUNT; bucketId++) {
            BigDecimal balanceForBucket = bucketId == 0 ? bucketBalance.add(remainder) : bucketBalance;
            jdbcTemplate.update(sql, bankCode, bucketId, balanceForBucket);
        }
    }

    private int bucketCount(String bankCode) {
        Integer count = jdbcTemplate.queryForObject(COUNT_BUCKETS_SQL, Integer.class, bankCode);
        return count == null ? 0 : count;
    }

    @Override
    public BigDecimal getAvailableFunds(String bankCode) {
        if (bucketCount(bankCode) == 0) {
            throw new IllegalStateException("Settlement account not found");
        }

        BigDecimal balance = jdbcTemplate.queryForObject(SUM_BUCKETS_SQL, BigDecimal.class, bankCode);
        if (balance == null) {
            throw new IllegalStateException("Settlement account not found");
        }
        return balance;
    }

    @Override
    public void deductFunds(String bankCode, BigDecimal amount) {
        int updatedRows = jdbcTemplate.update(DEDUCT_BUCKET_SQL, amount, bankCode, amount);
        if (updatedRows == 0) {
            throw new RuntimeException("There was an error updating the balance for the given account");
        }
    }

    @Override
    public void addFunds(String bankCode, BigDecimal amount) {
        int updatedRows = jdbcTemplate.update(ADD_BUCKET_SQL, amount, bankCode);
        if (updatedRows == 0) {
            throw new RuntimeException("There was an error updating the balance for the given account");
        }
    }
}
