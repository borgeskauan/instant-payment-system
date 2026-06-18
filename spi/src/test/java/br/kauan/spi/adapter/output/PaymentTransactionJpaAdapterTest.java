package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTransactionJpaAdapterTest {

    @Test
    void updateStatusDelegatesToStatusOnlyUpdate() {
        PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
        PaymentTransactionRepositoryMapper repositoryMapper = mock(PaymentTransactionRepositoryMapper.class);
        PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
                paymentTransactionJpaClient,
                repositoryMapper
        );
        when(paymentTransactionJpaClient.updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS.name()))
                .thenReturn(1);

        adapter.updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);

        verify(paymentTransactionJpaClient).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS.name());
    }
}
