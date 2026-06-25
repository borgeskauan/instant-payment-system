package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
    void saveTransactionsPersistsSettlementFieldsUsingJdbcBatchInsert() {
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
