package br.kauan.spi.port.output;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundsJpaAdapter implements FundsRepository {

    private static final int BUCKET_COUNT = 16;

    private static final String COUNT_BUCKETS_SQL = """
            SELECT COUNT(*)
            FROM funds_bucket_entity
            WHERE bank_code = ?
            """;

    private static final String INSERT_OR_KEEP_BUCKET_SQL = """
            INSERT INTO funds_bucket_entity (bank_code, bucket_id, balance_cents)
            VALUES (?, ?, ?)
            ON CONFLICT (bank_code, bucket_id) DO NOTHING
            """;

    private static final String INSERT_OR_RESET_BUCKET_SQL = """
            INSERT INTO funds_bucket_entity (bank_code, bucket_id, balance_cents)
            VALUES (?, ?, ?)
            ON CONFLICT (bank_code, bucket_id) DO UPDATE
            SET balance_cents = EXCLUDED.balance_cents
            """;

    private static final String SUM_BUCKETS_SQL = """
            SELECT COALESCE(SUM(balance_cents), 0)
            FROM funds_bucket_entity
            WHERE bank_code = ?
            """;

    private static final String DEDUCT_BUCKET_SQL = """
            UPDATE funds_bucket_entity
            SET balance_cents = balance_cents - ?
            WHERE bank_code = ?
              AND bucket_id = 0
              AND balance_cents >= ?
            """;

    private static final String ADD_BUCKET_SQL = """
            UPDATE funds_bucket_entity
            SET balance_cents = balance_cents + ?
            WHERE bank_code = ?
              AND bucket_id = 0
            """;

    private final JdbcTemplate jdbcTemplate;

    public FundsJpaAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void provisionAccount(String bankCode, long balanceCents, boolean resetIfExists) {
        long bucketBalance = balanceCents / BUCKET_COUNT;
        long remainder = balanceCents % BUCKET_COUNT;
        String sql = resetIfExists ? INSERT_OR_RESET_BUCKET_SQL : INSERT_OR_KEEP_BUCKET_SQL;

        for (int bucketId = 0; bucketId < BUCKET_COUNT; bucketId++) {
            long balanceForBucket = bucketId == 0 ? bucketBalance + remainder : bucketBalance;
            jdbcTemplate.update(sql, bankCode, bucketId, balanceForBucket);
        }
    }

    private int bucketCount(String bankCode) {
        Integer count = jdbcTemplate.queryForObject(COUNT_BUCKETS_SQL, Integer.class, bankCode);
        return count == null ? 0 : count;
    }

    @Override
    public long getAvailableFundsCents(String bankCode) {
        if (bucketCount(bankCode) == 0) {
            throw new IllegalStateException("Settlement account not found");
        }

        Long balance = jdbcTemplate.queryForObject(SUM_BUCKETS_SQL, Long.class, bankCode);
        if (balance == null) {
            throw new IllegalStateException("Settlement account not found");
        }
        return balance;
    }

    @Override
    public void deductFunds(String bankCode, long amountCents) {
        int updatedRows = jdbcTemplate.update(DEDUCT_BUCKET_SQL, amountCents, bankCode, amountCents);
        if (updatedRows == 0) {
            throw new RuntimeException("There was an error updating the balance for the given account");
        }
    }

    @Override
    public void addFunds(String bankCode, long amountCents) {
        int updatedRows = jdbcTemplate.update(ADD_BUCKET_SQL, amountCents, bankCode);
        if (updatedRows == 0) {
            throw new RuntimeException("There was an error updating the balance for the given account");
        }
    }
}
