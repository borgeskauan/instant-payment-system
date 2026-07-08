package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

class StatusUpdater {

    private static final String MARK_ACCEPTED_IN_PROCESS_IF_WAITING_SQL = """
            UPDATE payment_transaction_entity
            SET status = ?
            WHERE payment_id = ?
              AND status = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    StatusUpdater(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void markAcceptedInProcessIfWaitingAcceptance(List<String> paymentIds) {
        if (paymentIds.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                MARK_ACCEPTED_IN_PROCESS_IF_WAITING_SQL,
                paymentIds,
                paymentIds.size(),
                (statement, paymentId) -> {
                    statement.setString(1, PaymentStatus.ACCEPTED_IN_PROCESS.name());
                    statement.setString(2, paymentId);
                    statement.setString(3, PaymentStatus.WAITING_ACCEPTANCE.name());
                }
        );
    }
}
