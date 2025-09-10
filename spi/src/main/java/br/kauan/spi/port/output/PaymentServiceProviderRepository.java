package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

public interface PaymentServiceProviderRepository {
    void sendPayment(String ispb, PaymentTransaction transaction);
}
