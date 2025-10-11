package br.kauan.spi.adapter.input;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final NotificationUseCase notificationUseCase;

    @PostMapping("/{ispb}/transfer")
    public Mono<Void> transfer(
            @PathVariable String ispb,
            @RequestBody Mono<FIToFICustomerCreditTransfer> request
    ) {
        return request
                .map(paymentTransactionMapper::fromRegulatoryRequest)
                .flatMap(transaction -> paymentTransactionProcessorUseCase.processTransactionBatch(ispb, transaction));
    }

    @PostMapping("/{ispb}/transfer/status")
    public Mono<Void> transferStatus(
            @PathVariable String ispb,
            @RequestBody Mono<FIToFIPaymentStatusReport> statusReport
    ) {
        return statusReport
                .map(statusReportMapper::fromRegulatoryReport)
                .flatMap(status -> paymentTransactionProcessorUseCase.processStatusBatch(ispb, status));
    }

    @GetMapping("/{ispb}/messages")
    public Mono<SpiNotification> getMessages(@PathVariable String ispb) {
        return notificationUseCase.getNotifications(ispb);
    }
}