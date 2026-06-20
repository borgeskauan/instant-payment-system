package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.SettlementRepository;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SettlementJdbcAdapter implements SettlementRepository {

    private static final int BUCKET_COUNT = 16;

    private static final String SETTLE_ACCEPTED_PAYMENT_SQL = """
            WITH tx AS (
                SELECT *
                FROM payment_transaction_entity
                WHERE payment_id = ? AND status = ?
            ),
            debited AS (
                UPDATE funds_bucket_entity f
                SET balance = f.balance - tx.amount
                FROM tx
                WHERE f.bank_code = tx.sender_bank_code
                  AND f.bucket_id = (ABS(hashtext(tx.payment_id)) % 16)
                  AND f.balance >= tx.amount
                RETURNING f.bank_code
            ),
            credited AS (
                UPDATE funds_bucket_entity f
                SET balance = f.balance + tx.amount
                FROM tx, debited
                WHERE f.bank_code = tx.receiver_bank_code
                  AND f.bucket_id = (ABS(hashtext(tx.payment_id)) % 16)
                RETURNING f.bank_code
            ),
            settled AS (
                UPDATE payment_transaction_entity p
                SET status = ?
                FROM tx, credited
                WHERE p.payment_id = tx.payment_id
                RETURNING p.payment_id, p.amount, p.sender_bank_code, p.receiver_bank_code
            )
            SELECT payment_id, amount, sender_bank_code, receiver_bank_code
            FROM settled
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PaymentTransactionRepositoryMapper repositoryMapper;

    public SettlementJdbcAdapter(JdbcTemplate jdbcTemplate, PaymentTransactionRepositoryMapper repositoryMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.repositoryMapper = repositoryMapper;
    }

    @Override
    public Optional<PaymentTransaction> settleAcceptedPayment(
            String paymentId,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    ) {
        var settledPayments = jdbcTemplate.query(
                SETTLE_ACCEPTED_PAYMENT_SQL,
                this::toPaymentTransactionFromResultSet,
                paymentId,
                currentStatus.name(),
                settledStatus.name()
        );

        if (settledPayments.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(settledPayments.getFirst());
    }

    @Override
    public List<PaymentTransaction> settleAcceptedPayments(
            List<String> paymentIds,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    ) {
        List<String> uniquePaymentIds = paymentIds.stream()
                .distinct()
                .sorted()
                .toList();

        if (uniquePaymentIds.isEmpty()) {
            return List.of();
        }

        List<SettlementCandidate> candidates = findRelevantTransactions(uniquePaymentIds, currentStatus, settledStatus);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<SettlementCandidate> waitingCandidates = candidates.stream()
                .filter(candidate -> candidate.status().equals(currentStatus.name()))
                .toList();
        List<SettlementCandidate> alreadySettledCandidates = candidates.stream()
                .filter(candidate -> candidate.status().equals(settledStatus.name()))
                .toList();

        if (waitingCandidates.isEmpty()) {
            return candidates.stream()
                    .map(this::toPaymentTransaction)
                    .toList();
        }

        List<BucketKey> bucketKeys = waitingCandidates.stream()
                .flatMap(candidate -> List.of(
                        new BucketKey(candidate.senderBankCode(), candidate.bucketId()),
                        new BucketKey(candidate.receiverBankCode(), candidate.bucketId())
                ).stream())
                .distinct()
                .sorted()
                .toList();

        Map<BucketKey, BigDecimal> lockedBalances = lockBuckets(bucketKeys);
        if (lockedBalances.size() != bucketKeys.size()) {
            return alreadySettledCandidates.stream()
                    .map(this::toPaymentTransaction)
                    .toList();
        }

        Map<BucketKey, BigDecimal> debitTotals = aggregateDebits(waitingCandidates);
        boolean hasEnoughFunds = debitTotals.entrySet().stream()
                .allMatch(entry -> lockedBalances.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                        .compareTo(entry.getValue()) >= 0);

        if (!hasEnoughFunds) {
            return alreadySettledCandidates.stream()
                    .map(this::toPaymentTransaction)
                    .toList();
        }

        Map<BucketKey, BigDecimal> deltas = aggregateBalanceDeltas(waitingCandidates);
        applyBalanceDeltas(deltas);
        markSettled(waitingCandidates, currentStatus, settledStatus);

        return candidates.stream()
                .map(this::toPaymentTransaction)
                .toList();
    }

    private List<SettlementCandidate> findRelevantTransactions(
            List<String> paymentIds,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    ) {
        String sql = """
                WITH requested(payment_id) AS (
                    SELECT unnest(?::text[])
                )
                SELECT p.payment_id,
                       p.amount,
                       p.sender_bank_code,
                       p.receiver_bank_code,
                       p.status,
                       (ABS(hashtext(p.payment_id)) % ?) AS bucket_id
                FROM payment_transaction_entity p
                JOIN requested r ON r.payment_id = p.payment_id
                WHERE p.status IN (?, ?)
                ORDER BY p.payment_id
                FOR UPDATE OF p
                """;

        return jdbcTemplate.execute((ConnectionCallback<List<SettlementCandidate>>) connection -> {
            Array paymentIdArray = connection.createArrayOf("text", paymentIds.toArray(String[]::new));
            try (var statement = connection.prepareStatement(sql)) {
                statement.setArray(1, paymentIdArray);
                statement.setInt(2, BUCKET_COUNT);
                statement.setString(3, currentStatus.name());
                statement.setString(4, settledStatus.name());

                try (ResultSet resultSet = statement.executeQuery()) {
                    List<SettlementCandidate> candidates = new ArrayList<>();
                    int rowNumber = 0;
                    while (resultSet.next()) {
                        candidates.add(toSettlementCandidate(resultSet, rowNumber++));
                    }
                    return candidates;
                }
            } finally {
                paymentIdArray.free();
            }
        });
    }

    private Map<BucketKey, BigDecimal> lockBuckets(List<BucketKey> bucketKeys) {
        String sql = """
                WITH requested(bank_code, bucket_id) AS (
                    SELECT *
                    FROM unnest(?::text[], ?::int[])
                )
                SELECT f.bank_code, f.bucket_id, f.balance
                FROM funds_bucket_entity f
                JOIN requested r
                  ON r.bank_code = f.bank_code
                 AND r.bucket_id = f.bucket_id
                ORDER BY f.bank_code, f.bucket_id
                FOR UPDATE OF f
                """;

        return jdbcTemplate.execute((ConnectionCallback<Map<BucketKey, BigDecimal>>) connection -> {
            Array bankCodeArray = connection.createArrayOf(
                    "text",
                    bucketKeys.stream().map(BucketKey::bankCode).toArray(String[]::new)
            );
            Array bucketIdArray = connection.createArrayOf(
                    "integer",
                    bucketKeys.stream().map(BucketKey::bucketId).toArray(Integer[]::new)
            );
            try (var statement = connection.prepareStatement(sql)) {
                statement.setArray(1, bankCodeArray);
                statement.setArray(2, bucketIdArray);

                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<BucketKey, BigDecimal> balances = new HashMap<>();
                    while (resultSet.next()) {
                        balances.put(
                                new BucketKey(resultSet.getString("bank_code"), resultSet.getInt("bucket_id")),
                                resultSet.getBigDecimal("balance")
                        );
                    }
                    return balances;
                }
            } finally {
                bankCodeArray.free();
                bucketIdArray.free();
            }
        });
    }

    private Map<BucketKey, BigDecimal> aggregateDebits(List<SettlementCandidate> candidates) {
        Map<BucketKey, BigDecimal> debitTotals = new HashMap<>();
        candidates.forEach(candidate -> debitTotals.merge(
                new BucketKey(candidate.senderBankCode(), candidate.bucketId()),
                candidate.amount(),
                BigDecimal::add
        ));
        return debitTotals;
    }

    private Map<BucketKey, BigDecimal> aggregateBalanceDeltas(List<SettlementCandidate> candidates) {
        Map<BucketKey, BigDecimal> deltas = new HashMap<>();
        candidates.forEach(candidate -> {
            deltas.merge(
                    new BucketKey(candidate.senderBankCode(), candidate.bucketId()),
                    candidate.amount().negate(),
                    BigDecimal::add
            );
            deltas.merge(
                    new BucketKey(candidate.receiverBankCode(), candidate.bucketId()),
                    candidate.amount(),
                    BigDecimal::add
            );
        });
        return deltas;
    }

    private void applyBalanceDeltas(Map<BucketKey, BigDecimal> deltas) {
        String sql = """
                UPDATE funds_bucket_entity
                SET balance = balance + ?
                WHERE bank_code = ?
                  AND bucket_id = ?
                """;

        List<Map.Entry<BucketKey, BigDecimal>> orderedDeltas = deltas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        jdbcTemplate.batchUpdate(sql, orderedDeltas, orderedDeltas.size(), (statement, entry) -> {
            statement.setBigDecimal(1, entry.getValue());
            statement.setString(2, entry.getKey().bankCode());
            statement.setInt(3, entry.getKey().bucketId());
        });
    }

    private void markSettled(
            List<SettlementCandidate> candidates,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    ) {
        String sql = """
                UPDATE payment_transaction_entity
                SET status = ?
                WHERE status = ?
                  AND payment_id = ANY(?::text[])
                """;

        jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            Array paymentIdArray = connection.createArrayOf(
                    "text",
                    candidates.stream().map(SettlementCandidate::paymentId).toArray(String[]::new)
            );
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, settledStatus.name());
                statement.setString(2, currentStatus.name());
                statement.setArray(3, paymentIdArray);
                return statement.executeUpdate();
            } finally {
                paymentIdArray.free();
            }
        });
    }

    private SettlementCandidate toSettlementCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SettlementCandidate(
                resultSet.getString("payment_id"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("sender_bank_code"),
                resultSet.getString("receiver_bank_code"),
                resultSet.getString("status"),
                resultSet.getInt("bucket_id")
        );
    }

    private PaymentTransaction toPaymentTransactionFromResultSet(ResultSet resultSet, int rowNumber) throws SQLException {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId(resultSet.getString("payment_id"));
        entity.setAmount(resultSet.getBigDecimal("amount"));
        entity.setSenderBankCode(resultSet.getString("sender_bank_code"));
        entity.setReceiverBankCode(resultSet.getString("receiver_bank_code"));
        return repositoryMapper.toDomain(entity);
    }

    private PaymentTransaction toPaymentTransaction(SettlementCandidate candidate) {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId(candidate.paymentId());
        entity.setAmount(candidate.amount());
        entity.setSenderBankCode(candidate.senderBankCode());
        entity.setReceiverBankCode(candidate.receiverBankCode());
        return repositoryMapper.toDomain(entity);
    }

    private record BucketKey(String bankCode, int bucketId) implements Comparable<BucketKey> {

        @Override
        public int compareTo(BucketKey other) {
            return Comparator.comparing(BucketKey::bankCode)
                    .thenComparingInt(BucketKey::bucketId)
                    .compare(this, other);
        }
    }

    private record SettlementCandidate(
            String paymentId,
            BigDecimal amount,
            String senderBankCode,
            String receiverBankCode,
            String status,
            int bucketId
    ) {
    }
}
