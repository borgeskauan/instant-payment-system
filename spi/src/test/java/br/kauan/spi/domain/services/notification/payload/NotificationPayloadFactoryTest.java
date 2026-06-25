package br.kauan.spi.domain.services.notification.payload;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.NotificationContentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPayloadFactoryTest {

    private final NotificationPayloadFactory factory = new NotificationPayloadFactory();
    private final NotificationContentSerializer serializer = new NotificationContentSerializer();

    @Test
    void buildsPaymentNotificationWithExistingJsonShape() {
        Object payload = factory.paymentNotification(List.of(paymentTransaction("E2E-1")));

        assertThat(serializer.serialize(payload)).hasValueSatisfying(json -> assertThat(json)
                .contains("\"GrpHdr\"")
                .contains("\"NbOfTxs\":1")
                .contains("\"CdtTrfTxInf\"")
                .contains("\"EndToEndId\":\"E2E-1\"")
                .contains("\"IntrBkSttlmAmt\":{\"value\":10.00,\"Ccy\":\"BRL\"}")
                .contains("\"DbtrAcct\":{\"Id\":{\"Othr\":{\"Id\":\"000123\",\"Issr\":\"0012\"}}")
                .contains("\"CdtrAcct\":{\"Id\":{\"Othr\":{\"Id\":\"000123\",\"Issr\":\"0012\"}}"));
    }

    @Test
    void buildsStatusNotificationWithExistingJsonShape() {
        Object payload = factory.statusNotification(List.of(StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER)
                .build()));

        assertThat(serializer.serialize(payload)).hasValueSatisfying(json -> assertThat(json)
                .contains("\"GrpHdr\"")
                .contains("\"NbOfTxs\":1")
                .contains("\"TxInfAndSts\"")
                .contains("\"OrgnlEndToEndId\":\"E2E-1\"")
                .contains("\"TxSts\":\"ACSC\""));
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .currency("BRL")
                .description("test")
                .sender(party("10000001"))
                .receiver(party("20000001"))
                .build();
    }

    private static Party party(String bankCode) {
        return Party.builder()
                .name("Name")
                .taxId("12345678900")
                .pixKey("+5511999999999")
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("000123")
                        .branch("0012")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
