package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.springframework.stereotype.Component;

@Component
public class NotificationBuilder {

    public StatusReport buildStatusReport(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
        return StatusReport.builder()
                .originalPaymentId(paymentTransaction.getPaymentId())
                .status(paymentStatus)
                .build();
    }
}