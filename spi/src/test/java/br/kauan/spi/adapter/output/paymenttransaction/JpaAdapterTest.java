package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JpaAdapterTest {

    @Test
    void adapterDoesNotExposeSingleTransactionSave() {
        assertThrows(NoSuchMethodException.class,
                () -> JpaAdapter.class.getMethod(
                        "saveTransaction",
                        PaymentTransactionCommand.class,
                        PaymentState.class
                ));
    }

    @Test
    void adapterDoesNotExposeSingleStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> JpaAdapter.class.getMethod(
                        "updateStatus",
                        String.class,
                        PaymentState.class
                ));
    }

    @Test
    void adapterDoesNotExposeBlindBatchStatusUpdate() {
        assertThrows(NoSuchMethodException.class,
                () -> JpaAdapter.class.getMethod(
                        "updateStatuses",
                        List.class,
                        PaymentState.class
                ));
    }

    @Test
    void mapperBuildsPartiesWhenOnlyBankCodesAreAvailable() {
        Entity entity = new Entity();
        entity.setPaymentId("E2E-1");
        entity.setAmountCents(1000L);
        entity.setSenderBankCode("11111111");
        entity.setReceiverBankCode("22222222");

        PaymentTransactionCommand transaction = new Mapper().toDomain(entity);

        assertThat(transaction.getSender().getAccount().getBankCode()).isEqualTo("11111111");
        assertThat(transaction.getReceiver().getAccount().getBankCode()).isEqualTo("22222222");
    }
}
