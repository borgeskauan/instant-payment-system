package br.kauan.paymentserviceprovider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "bank.code=12345678",
        "notification.gateway.client-enabled=false",
        "notification.gateway.reconnect-delay=1m"
})
class PaymentServiceProviderApplicationTests {

    @Test
    void contextLoads() {
    }

}
