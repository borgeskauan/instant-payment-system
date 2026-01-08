package br.kauan.kafkaproducer.input;

import br.kauan.kafkaproducer.domain.QueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class PaymentController {

    private final QueueService queueService;

    public PaymentController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping(value = "/{ispb}/transfer", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<Void> transfer(@PathVariable String ispb, @RequestBody byte[] payload) {
        log.info("[PIX FLOW - Step 3] Kafka Producer received transfer request for ISPB: {}, payload size: {} bytes", 
                ispb, payload.length);
        log.debug("[PIX FLOW - Step 3] Forwarding PACS.008 message to Kafka topic");
        return queueService.sendBytes(payload);
    }

    @PostMapping(value = "/{ispb}/transfer/status", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<Void> transferStatus(@PathVariable String ispb, @RequestBody byte[] payload) {
        log.info("[PIX FLOW - Step 5] Kafka Producer received status report for ISPB: {}, payload size: {} bytes", 
                ispb, payload.length);
        log.debug("[PIX FLOW - Step 5] Forwarding PACS.002 message to Kafka topic");
        return queueService.sendBytes(payload);
    }
}
