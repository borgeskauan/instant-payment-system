package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcPaymentTransactionRepositoryTest {

    @Test
    void adapterDoesNotExposeSingleTransactionSave() {
        assertThrows(NoSuchMethodException.class,
                () -> JdbcPaymentTransactionRepository.class.getMethod(
                        "saveTransaction",
                        PaymentTransactionCommand.class,
                        PaymentState.class
                ));
    }

    @Test
    void adapterDoesNotExposeSingleStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> JdbcPaymentTransactionRepository.class.getMethod(
                        "updateStatus",
                        String.class,
                        PaymentState.class
                ));
    }

    @Test
    void adapterDoesNotExposeBlindBatchStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> JdbcPaymentTransactionRepository.class.getMethod(
                        "updateStatuses",
                        List.class,
                        PaymentState.class
                ));
    }

    @Test
    void mapperBuildsPartiesWhenOnlyBankCodesAreAvailable() {
        PaymentTransactionRow row = new PaymentTransactionRow();
        row.setPaymentId("E2E-1");
        row.setAmountCents(1000L);
        row.setSenderBankCode("11111111");
        row.setReceiverBankCode("22222222");

        PaymentTransactionCommand transaction = new PaymentTransactionRowMapper().toDomain(row);

        assertThat(transaction.getSender().getAccount().getBankCode()).isEqualTo("11111111");
        assertThat(transaction.getReceiver().getAccount().getBankCode()).isEqualTo("22222222");
    }
}
