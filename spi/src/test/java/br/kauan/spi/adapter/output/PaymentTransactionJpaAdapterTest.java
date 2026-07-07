package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTransactionJpaAdapterTest {

    @Test
    void adapterDoesNotExposeSingleTransactionSave() {
        assertThrows(NoSuchMethodException.class,
                () -> PaymentTransactionJpaAdapter.class.getMethod(
                        "saveTransaction",
                        PaymentTransactionCommand.class,
                        PaymentStatus.class
                ));
    }

    @Test
    void updateStatusesDelegatesToStatusOnlyBatchUpdate() {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = mock(PaymentTransactionRepositoryMapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );
        when(jdbcTemplate.batchUpdate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<List<String>>any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
        )).thenReturn(new int[][]{{1, 1}});

        adapter.updateStatuses(List.of("E2E-1", "E2E-2"), PaymentStatus.ACCEPTED_IN_PROCESS);

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("UPDATE payment_transaction_entity"),
                org.mockito.ArgumentMatchers.eq(List.of("E2E-1", "E2E-2")),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
        );
    }

    @Test
    void adapterDoesNotExposeSingleStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> PaymentTransactionJpaAdapter.class.getMethod(
                        "updateStatus",
                        String.class,
                        PaymentStatus.class
                ));
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsPersistsOnlySettlementFieldsAndFingerprintUsingOneSqlQuery()
            throws Exception {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = new PaymentTransactionRepositoryMapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "33333333", "44444444");

        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.any(RowMapper.class)
        )).thenAnswer(invocation -> List.of(
                mapActionRow(invocation.getArgument(2), 0, "ACCEPTANCE_REQUEST"),
                mapActionRow(invocation.getArgument(2), 1, "ACCEPTANCE_REQUEST")
        ));

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.any(RowMapper.class)
        );
        assertThat(sqlCaptor.getValue())
                .contains("payment_id")
                .contains("amount_cents")
                .contains("status")
                .contains("sender_bank_code")
                .contains("receiver_bank_code")
                .contains("request_fingerprint")
                .contains("request_fingerprint_version")
                .contains("COUNT(DISTINCT (request_fingerprint_version, request_fingerprint))")
                .contains("ON CONFLICT (payment_id) DO NOTHING")
                .doesNotContain("sender_name")
                .doesNotContain("sender_tax_id")
                .doesNotContain("sender_pix_key")
                .doesNotContain("sender_account_number")
                .doesNotContain("receiver_name")
                .doesNotContain("receiver_tax_id")
                .doesNotContain("receiver_pix_key")
                .doesNotContain("receiver_account_number")
                .doesNotContain("currency")
                .doesNotContain("description");
        assertThat(result.acceptanceRequests()).containsExactly(first, second);
        assertThat(result.divergentDuplicates()).isEmpty();
        verify(paymentTransactionJpaClient, never()).save(any());
        verify(jdbcTemplate, never()).batchUpdate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<List<PaymentTransactionCommand>>any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
        );
    }

    @Test
    void storeAndClassifyIncomingPaymentRequestsMapsSqlActionsBackToOriginalCommandsByOrdinal() throws Exception {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = new PaymentTransactionRepositoryMapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransactionCommand first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-2", "33333333", "44444444");
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.any(RowMapper.class)
        )).thenAnswer(invocation -> List.of(
                mapActionRow(invocation.getArgument(2), 1, "DIVERGENT_DUPLICATE"),
                mapActionRow(invocation.getArgument(2), 0, "ACCEPTANCE_REQUEST")
        ));

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).containsExactly(second);
    }

    private static Object mapActionRow(RowMapper<?> rowMapper, int ordinal, String action) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getInt("ordinal")).thenReturn(ordinal);
        when(resultSet.getString("action")).thenReturn(action);
        return rowMapper.mapRow(resultSet, ordinal);
    }

    @Test
    void mapperBuildsPartiesWhenOnlyBankCodesAreAvailable() {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId("E2E-1");
        entity.setAmountCents(1000L);
        entity.setSenderBankCode("11111111");
        entity.setReceiverBankCode("22222222");

        PaymentTransactionCommand transaction = new PaymentTransactionRepositoryMapper().toDomain(entity);

        assertThat(transaction.getSender().getAccount().getBankCode()).isEqualTo("11111111");
        assertThat(transaction.getReceiver().getAccount().getBankCode()).isEqualTo("22222222");
    }

    private static PaymentTransactionCommand paymentTransaction() {
        return paymentTransaction("E2E-1", "11111111", "22222222");
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .currency("BRL")
                .description("test")
                .sender(party(senderBankCode))
                .receiver(party(receiverBankCode))
                .build();
    }

    private static Party party(String bankCode) {
        return Party.builder()
                .name("Name")
                .taxId("123")
                .pixKey("pix-" + bankCode)
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
