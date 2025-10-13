package br.kauan.spi.adapter.input;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.metrics.PaymentMetrics;
import br.kauan.spi.port.input.NotificationUseCase;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final NotificationUseCase notificationUseCase;
    private final PaymentMetrics paymentMetrics;

    @PostMapping("/{ispb}/transfer")
    public Mono<Void> transfer(
            @PathVariable String ispb,
            @RequestBody Mono<FIToFICustomerCreditTransfer> request
    ) {
        Instant start = Instant.now();

        return request
                .map(paymentTransactionMapper::fromRegulatoryRequest)
                .flatMap(transaction -> paymentTransactionProcessorUseCase.processTransactionBatch(ispb, transaction))
                .doOnSuccess(unused -> {
                    long duration = Duration.between(start, Instant.now()).toMillis();
                    paymentMetrics.recordTransferSuccess(duration, TimeUnit.MILLISECONDS);
                })
                .doOnError(error -> {
                    long duration = Duration.between(start, Instant.now()).toMillis();
                    paymentMetrics.recordTransferError(duration, TimeUnit.MILLISECONDS);
                });
    }

    @PostMapping("/{ispb}/transfer/status")
    public Mono<Void> transferStatus(
            @PathVariable String ispb,
            @RequestBody Mono<FIToFIPaymentStatusReport> statusReport
    ) {
        Instant start = Instant.now();

        return statusReport
                .map(statusReportMapper::fromRegulatoryReport)
                .flatMap(status -> paymentTransactionProcessorUseCase.processStatusBatch(ispb, status))
                .doOnSuccess(unused -> {
                    long duration = Duration.between(start, Instant.now()).toMillis();
                    paymentMetrics.recordTransferStatusSuccess(duration, TimeUnit.MILLISECONDS);
                })
                .doOnError(error -> {
                    long duration = Duration.between(start, Instant.now()).toMillis();
                    paymentMetrics.recordTransferStatusError(duration, TimeUnit.MILLISECONDS);
                });
    }

    @GetMapping("/{ispb}/messages")
    public Mono<SpiNotification> getMessages(@PathVariable String ispb) {
        Instant start = Instant.now();

        return notificationUseCase.getNotifications(ispb)
                .doOnSuccess(result -> {
                    long duration = Duration.between(start, Instant.now()).toMillis();
                    paymentMetrics.recordGetMessagesSuccess(duration, TimeUnit.MILLISECONDS);
                })
                .doOnError(error -> {
                    long duration = Duration.between(start, Instant.now()).toMillis();
                    paymentMetrics.recordGetMessagesError(duration, TimeUnit.MILLISECONDS);
                });
    }
}