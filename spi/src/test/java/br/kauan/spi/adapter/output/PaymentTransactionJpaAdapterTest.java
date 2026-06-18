package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

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
    void saveTransactionUsesDirectJdbcInsertInsteadOfJpaSave() {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = new PaymentTransactionRepositoryMapper();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper,
                jdbcTemplate
        );

        adapter.saveTransaction(paymentTransaction(), PaymentStatus.WAITING_ACCEPTANCE);

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class)
        );
        verify(paymentTransactionJpaClient, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static PaymentTransaction paymentTransaction() {
        return PaymentTransaction.builder()
                .paymentId("E2E-1")
                .amount(BigDecimal.TEN)
                .currency("BRL")
                .description("test")
                .sender(party("11111111"))
                .receiver(party("22222222"))
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
