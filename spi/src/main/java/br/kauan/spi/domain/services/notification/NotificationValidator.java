package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.springframework.stereotype.Component;

@Component
public class NotificationValidator {

    public void validatePaymentTransaction(PaymentTransaction paymentTransaction) {
        if (paymentTransaction == null) {
            throw new IllegalArgumentException("Payment transaction cannot be null");
        }
        if (paymentTransaction.getPaymentId() == null) {
            throw new IllegalArgumentException("Payment ID cannot be null");
        }
    }

    public void validateIspb(String ispb) {
        if (ispb == null || ispb.trim().isEmpty()) {
            throw new IllegalArgumentException("ISPB cannot be null or empty");
        }
    }
}