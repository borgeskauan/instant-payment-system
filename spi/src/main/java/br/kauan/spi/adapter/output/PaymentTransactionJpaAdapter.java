package br.kauan.spi.adapter.output;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class PaymentTransactionJpaAdapter implements PaymentTransactionRepository {

    private static final String ACCEPTANCE_REQUEST = "ACCEPTANCE_REQUEST";
    private static final String DIVERGENT_DUPLICATE = "DIVERGENT_DUPLICATE";

    private static final String UPDATE_PAYMENT_STATUS_SQL = """
            UPDATE payment_transaction_entity
            SET status = ?
            WHERE payment_id = ?
            """;

    private static final String PERSISTENCE_SQL_TEMPLATE = """
            WITH incoming (
                ordinal,
                payment_id,
                amount_cents,
                status,
                sender_bank_code,
                receiver_bank_code,
                request_fingerprint,
                request_fingerprint_version
            ) AS (
                VALUES
                %s
            ),
            identity_counts AS (
                SELECT
                    payment_id,
                    COUNT(DISTINCT (request_fingerprint_version, request_fingerprint)) AS identity_count
                FROM incoming
                GROUP BY payment_id
            ),
            same_batch_divergent_actions AS (
                SELECT i.ordinal, 'DIVERGENT_DUPLICATE'::text AS action
                FROM incoming i
                JOIN identity_counts c ON c.payment_id = i.payment_id
                WHERE c.identity_count > 1
            ),
            candidates AS (
                SELECT i.*
                FROM incoming i
                JOIN identity_counts c ON c.payment_id = i.payment_id
                WHERE c.identity_count = 1
            ),
            logical_candidates AS (
                SELECT DISTINCT ON (payment_id) *
                FROM candidates
                ORDER BY payment_id, ordinal
            ),
            inserted AS (
                INSERT INTO payment_transaction_entity (
                    payment_id,
                    amount_cents,
                    status,
                    sender_bank_code,
                    receiver_bank_code,
                    request_fingerprint,
                    request_fingerprint_version
                )
                SELECT
                    payment_id,
                    amount_cents,
                    status,
                    sender_bank_code,
                    receiver_bank_code,
                    request_fingerprint,
                    request_fingerprint_version
                FROM logical_candidates
                ON CONFLICT (payment_id) DO NOTHING
                RETURNING payment_id
            ),
            inserted_actions AS (
                SELECT lc.ordinal, 'ACCEPTANCE_REQUEST'::text AS action
                FROM logical_candidates lc
                JOIN inserted i ON i.payment_id = lc.payment_id
            ),
            existing_rows AS (
                SELECT
                    lc.payment_id,
                    lc.ordinal,
                    lc.request_fingerprint,
                    lc.request_fingerprint_version,
                    p.status AS existing_status,
                    p.request_fingerprint AS existing_fingerprint,
                    p.request_fingerprint_version AS existing_fingerprint_version
                FROM logical_candidates lc
                JOIN payment_transaction_entity p ON p.payment_id = lc.payment_id
                LEFT JOIN inserted i ON i.payment_id = lc.payment_id
                WHERE i.payment_id IS NULL
            ),
            existing_waiting_acceptance_actions AS (
                SELECT er.ordinal, 'ACCEPTANCE_REQUEST'::text AS action
                FROM existing_rows er
                WHERE er.existing_fingerprint_version = er.request_fingerprint_version
                  AND er.existing_fingerprint = er.request_fingerprint
                  AND er.existing_status = ?
            ),
            existing_divergent_payment_ids AS (
                SELECT er.payment_id
                FROM existing_rows er
                WHERE er.existing_fingerprint_version IS DISTINCT FROM er.request_fingerprint_version
                   OR er.existing_fingerprint IS DISTINCT FROM er.request_fingerprint
            ),
            existing_divergent_actions AS (
                SELECT c.ordinal, 'DIVERGENT_DUPLICATE'::text AS action
                FROM candidates c
                JOIN existing_divergent_payment_ids d ON d.payment_id = c.payment_id
            )
            SELECT ordinal, action FROM same_batch_divergent_actions
            UNION ALL
            SELECT ordinal, action FROM inserted_actions
            UNION ALL
            SELECT ordinal, action FROM existing_waiting_acceptance_actions
            UNION ALL
            SELECT ordinal, action FROM existing_divergent_actions
            ORDER BY ordinal
            """;

    private final PaymentTransactionJpaClient paymentTransactionJpaClient;
    private final PaymentTransactionRepositoryMapper repositoryMapper;
    private final JdbcTemplate jdbcTemplate;

    public PaymentTransactionJpaAdapter(
            PaymentTransactionJpaClient paymentTransactionJpaClient,
            PaymentTransactionRepositoryMapper repositoryMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.paymentTransactionJpaClient = paymentTransactionJpaClient;
        this.repositoryMapper = repositoryMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PaymentTransactionPersistenceResult storeAndClassifyIncomingPaymentRequests(
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        if (paymentTransactions.isEmpty()) {
            return new PaymentTransactionPersistenceResult(List.of(), List.of());
        }

        List<PaymentTransactionCommand> acceptanceRequests = new ArrayList<>();
        List<PaymentTransactionCommand> divergentDuplicates = new ArrayList<>();

        for (PersistenceActionRow actionRow : persistAndClassify(paymentTransactions)) {
            PaymentTransactionCommand paymentTransaction = paymentTransactions.get(actionRow.ordinal());
            switch (actionRow.action()) {
                case ACCEPTANCE_REQUEST -> acceptanceRequests.add(paymentTransaction);
                case DIVERGENT_DUPLICATE -> divergentDuplicates.add(paymentTransaction);
                default -> throw new IllegalStateException("Unknown payment persistence action: " + actionRow.action());
            }
        }

        return new PaymentTransactionPersistenceResult(acceptanceRequests, divergentDuplicates);
    }

    private List<PersistenceActionRow> persistAndClassify(
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        List<IncomingPaymentRow> incomingRows = incomingRows(paymentTransactions);
        return jdbcTemplate.query(
                persistenceSql(incomingRows.size()),
                statement -> {
                    int parameterIndex = 1;
                    for (IncomingPaymentRow incomingRow : incomingRows) {
                        statement.setInt(parameterIndex++, incomingRow.ordinal());
                        statement.setString(parameterIndex++, incomingRow.paymentTransaction().getPaymentId());
                        statement.setLong(parameterIndex++, incomingRow.paymentTransaction().getAmountCents());
                        statement.setString(parameterIndex++, PaymentStatus.WAITING_ACCEPTANCE.name());
                        statement.setString(parameterIndex++, Utils.getBankCode(incomingRow.paymentTransaction().getSender()));
                        statement.setString(parameterIndex++, Utils.getBankCode(incomingRow.paymentTransaction().getReceiver()));
                        statement.setString(parameterIndex++, incomingRow.requestFingerprint());
                        statement.setString(parameterIndex++, incomingRow.requestFingerprintVersion());
                    }
                    statement.setString(parameterIndex, PaymentStatus.WAITING_ACCEPTANCE.name());
                },
                (resultSet, rowNumber) -> new PersistenceActionRow(
                        resultSet.getInt("ordinal"),
                        resultSet.getString("action")
                )
        );
    }

    private List<IncomingPaymentRow> incomingRows(List<PaymentTransactionCommand> paymentTransactions) {
        List<IncomingPaymentRow> incomingRows = new ArrayList<>(paymentTransactions.size());
        for (int ordinal = 0; ordinal < paymentTransactions.size(); ordinal++) {
            PaymentTransactionCommand paymentTransaction = paymentTransactions.get(ordinal);
            incomingRows.add(new IncomingPaymentRow(
                    ordinal,
                    paymentTransaction,
                    PaymentTransactionFingerprint.from(paymentTransaction),
                    PaymentTransactionFingerprint.VERSION
            ));
        }
        return incomingRows;
    }

    private String persistenceSql(int rowCount) {
        String values = java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(ignored -> "(?, ?, ?, ?, ?, ?, ?, ?)")
                .collect(Collectors.joining(",\n"));

        return PERSISTENCE_SQL_TEMPLATE.formatted(values);
    }

    @Override
    public void updateStatuses(List<String> paymentIds, PaymentStatus paymentStatus) {
        if (paymentIds.isEmpty()) {
            return;
        }

        int[][] updatedRows = jdbcTemplate.batchUpdate(
                UPDATE_PAYMENT_STATUS_SQL,
                paymentIds,
                paymentIds.size(),
                (statement, paymentId) -> {
                    statement.setString(1, paymentStatus.name());
                    statement.setString(2, paymentId);
                }
        );

        for (int batchIndex = 0; batchIndex < updatedRows.length; batchIndex++) {
            for (int updateCount : updatedRows[batchIndex]) {
                if (updateCount == 0) {
                    throw new IllegalStateException("Payment transaction not found while updating status");
                }
            }
        }
    }

    @Override
    public Optional<PaymentTransactionCommand> findById(String originalPaymentId) {
        return paymentTransactionJpaClient.findById(originalPaymentId).map(repositoryMapper::toDomain);
    }

    private record IncomingPaymentRow(
            int ordinal,
            PaymentTransactionCommand paymentTransaction,
            String requestFingerprint,
            String requestFingerprintVersion
    ) {
    }

    private record PersistenceActionRow(int ordinal, String action) {
    }
}
