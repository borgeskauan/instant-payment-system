package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    public void sendConfirmationNotification(PaymentTransaction paymentTransaction) {

    }

    public void sendRejectionNotification(PaymentTransaction paymentTransaction) {

    }
}
