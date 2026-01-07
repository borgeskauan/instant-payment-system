package br.kauan.kafkaproducer.input;

import br.kauan.kafkaproducer.domain.QueueService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
public class PaymentController {

    private final QueueService queueService;

    public PaymentController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping(value = "/{ispb}/transfer", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<Void> transfer(@PathVariable String ispb, @RequestBody byte[] payload) {
        return queueService.sendBytes(payload);
    }

    @PostMapping(value = "/{ispb}/transfer/status", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<Void> transferStatus(@PathVariable String ispb, @RequestBody byte[] payload) {
        return queueService.sendBytes(payload);
    }
}
