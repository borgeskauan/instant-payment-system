package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.PaymentServiceProviderRepository;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentServiceProviderQueueAdapter implements PaymentServiceProviderRepository {
    @Override
    public void sendPayment(String ispb, PaymentTransaction transaction) {

    }
}
