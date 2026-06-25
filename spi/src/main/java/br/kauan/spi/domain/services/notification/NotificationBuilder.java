package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Component;

@Component
public class NotificationBuilder {

    public StatusReportCommand buildStatusReport(PaymentTransactionCommand paymentTransaction, PaymentStatus paymentStatus) {
        return StatusReportCommand.builder()
                .originalPaymentId(paymentTransaction.getPaymentId())
                .status(paymentStatus)
                .build();
    }
}