package br.kauan.spi.adapter.input;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
public class PaymentController {

    private final NotificationUseCase notificationUseCase;

    public PaymentController(NotificationUseCase notificationUseCase) {
        this.notificationUseCase = notificationUseCase;
    }

    // POST endpoints removed - messages now consumed from Kafka via PaymentMessageConsumer

    @GetMapping("/{ispb}/messages")
    public Mono<SpiNotification> getMessages(@PathVariable String ispb) {
//        return Mono.just(
//                SpiNotification.builder()
//                        .build()
//        );
//        return notificationUseCase.getNotifications(ispb);

        var spiNotification = SpiNotification.builder()
                .content(List.of(
                        "{\"GrpHdr\":{\"MsgId\":\"6ec7f42c-ba82-4fc5-a2ac-53c2461b6164\",\"CreDtTm\":1759624883291,\"NbOfTxs\":1},\"CdtTrfTxInf\":[{\"PmtId\":{\"EndToEndId\":\"PIX-TRANSFER-002\"},\"IntrBkSttlmAmt\":{\"value\":1500.50,\"Ccy\":\"BRL\"},\"Dbtr\":{\"Nm\":\"Maria Oliveira Lima\",\"Id\":{\"PrvtId\":{\"Othr\":{\"Id\":\"987.654.321-00\"}}}},\"DbtrAcct\":{\"Id\":{\"Othr\":{\"Id\":123456,\"Issr\":5678}},\"Tp\":{\"Cd\":\"CACC\"},\"Prxy\":{\"Id\":null}},\"DbtrAgt\":{\"FinInstnId\":{\"ClrSysMmbId\":{\"MmbId\":\"87654321\"}}},\"CdtrAgt\":{\"FinInstnId\":{\"ClrSysMmbId\":{\"MmbId\":\"12345678\"}}},\"Cdtr\":{\"Nm\":\"João Silva Santos\",\"Id\":{\"PrvtId\":{\"Othr\":{\"Id\":\"123.456.789-00\"}}}},\"CdtrAcct\":{\"Id\":{\"Othr\":{\"Id\":987654,\"Issr\":1234}},\"Tp\":{\"Cd\":\"CACC\"},\"Prxy\":{\"Id\":\"+5511999999999\"}},\"RmtInf\":{\"Ustrd\":\"Pagamento serviços prestados - Setembro/2025\"}}]}"
                ))
                .build();

        return Mono.just(spiNotification);

//        var deferredResult = new DeferredResult<SpiNotification>();
//        deferredResult.setResult(spiNotification);
//        return deferredResult;
    }
}
