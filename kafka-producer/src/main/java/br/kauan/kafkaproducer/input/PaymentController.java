package br.kauan.kafkaproducer.input;

import br.kauan.kafkaproducer.domain.QueueService;
import br.kauan.kafkaproducer.domain.SpiNotification;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
public class PaymentController {

    private final QueueService queueService;

    public PaymentController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping(value = "/{ispb}/transfer", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<Void> transfer(@PathVariable String ispb, @RequestBody byte[] payload) {
        return queueService.sendBytes(payload);
//        return Mono.empty();
    }

    @PostMapping(value = "/{ispb}/transfer/status", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<Void> transferStatus(@PathVariable String ispb, @RequestBody byte[] payload) {
        return queueService.sendBytes(payload);
//        return Mono.empty();
    }

    @GetMapping("/{ispb}/messages")
    public Mono<SpiNotification> getMessages(@PathVariable String ispb) {
////        return Mono.just(
////                SpiNotification.builder()
////                        .build()
////        );
////        return notificationUseCase.getNotifications(ispb);

        var spiNotification = SpiNotification.builder()
                .content(List.of(
                        "{\"GrpHdr\":{\"MsgId\":\"6ec7f42c-ba82-4fc5-a2ac-53c2461b6164\",\"CreDtTm\":1759624883291,\"NbOfTxs\":1},\"CdtTrfTxInf\":[{\"PmtId\":{\"EndToEndId\":\"PIX-TRANSFER-002\"},\"IntrBkSttlmAmt\":{\"value\":1500.50,\"Ccy\":\"BRL\"},\"Dbtr\":{\"Nm\":\"Maria Oliveira Lima\",\"Id\":{\"PrvtId\":{\"Othr\":{\"Id\":\"987.654.321-00\"}}}},\"DbtrAcct\":{\"Id\":{\"Othr\":{\"Id\":123456,\"Issr\":5678}},\"Tp\":{\"Cd\":\"CACC\"},\"Prxy\":{\"Id\":null}},\"DbtrAgt\":{\"FinInstnId\":{\"ClrSysMmbId\":{\"MmbId\":\"87654321\"}}},\"CdtrAgt\":{\"FinInstnId\":{\"ClrSysMmbId\":{\"MmbId\":\"12345678\"}}},\"Cdtr\":{\"Nm\":\"João Silva Santos\",\"Id\":{\"PrvtId\":{\"Othr\":{\"Id\":\"123.456.789-00\"}}}},\"CdtrAcct\":{\"Id\":{\"Othr\":{\"Id\":987654,\"Issr\":1234}},\"Tp\":{\"Cd\":\"CACC\"},\"Prxy\":{\"Id\":\"+5511999999999\"}},\"RmtInf\":{\"Ustrd\":\"Pagamento serviços prestados - Setembro/2025\"}}]}"
                ))
                .build();

        return Mono.just(spiNotification);

////        var deferredResult = new DeferredResult<SpiNotification>();
////        deferredResult.setResult(spiNotification);
////        return deferredResult;
    }
}
