package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTransactionJpaAdapterTest {

    @Test
    void updateStatusDelegatesToStatusOnlyUpdate() {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = mock(PaymentTransactionRepositoryMapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );
        when(paymentTransactionJpaClient.updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS.name()))
                .thenReturn(1);

        adapter.updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);

        verify(paymentTransactionJpaClient).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS.name());
    }

    @Test
    void saveTransactionPersistsOnlySettlementFieldsUsingJdbcBatchInsert() {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = new PaymentTransactionRepositoryMapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );

        adapter.saveTransaction(paymentTransaction(), PaymentStatus.WAITING_ACCEPTANCE);

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("sender_bank_code"),
                org.mockito.ArgumentMatchers.eq(List.of(paymentTransaction())),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
        );
        verify(paymentTransactionJpaClient, never()).save(any());
    }

    @Test
    void saveTransactionsPersistsSettlementFieldsUsingJdbcBatchInsert() {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = new PaymentTransactionRepositoryMapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );
        PaymentTransaction first = paymentTransaction("E2E-1", "11111111", "22222222");
        PaymentTransaction second = paymentTransaction("E2E-2", "33333333", "44444444");

        adapter.saveTransactions(List.of(first, second), PaymentStatus.WAITING_ACCEPTANCE);

        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("sender_bank_code"),
                org.mockito.ArgumentMatchers.eq(List.of(first, second)),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
        );
        verify(paymentTransactionJpaClient, never()).save(any());
    }

    @Test
    void mapperBuildsPartiesWhenOnlyBankCodesAreAvailable() {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId("E2E-1");
        entity.setAmount(BigDecimal.TEN);
        entity.setSenderBankCode("11111111");
        entity.setReceiverBankCode("22222222");

        PaymentTransaction transaction = new PaymentTransactionRepositoryMapper().toDomain(entity);

        assertThat(transaction.getSender().getAccount().getBankCode()).isEqualTo("11111111");
        assertThat(transaction.getReceiver().getAccount().getBankCode()).isEqualTo("22222222");
    }

    private static PaymentTransaction paymentTransaction() {
        return paymentTransaction("E2E-1", "11111111", "22222222");
    }

    private static PaymentTransaction paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransaction.builder()
                .paymentId(paymentId)
                .amount(BigDecimal.TEN)
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
                        .number(1L)
                        .branch(1)
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
