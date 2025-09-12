package br.kauan.spi.adapter.input;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.services.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;

    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;

    private final NotificationUseCase notificationUseCase;

    public PaymentController(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            NotificationUseCase notificationUseCase
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.notificationUseCase = notificationUseCase;
    }

    @PostMapping("/{ispb}/transfer")
    public void transfer(@PathVariable String ispb, @RequestBody FIToFICustomerCreditTransfer request) {
        var transaction = paymentTransactionMapper.fromRegulatoryRequest(request);
        paymentTransactionProcessorUseCase.processTransactionBatch(ispb, transaction);
    }

    @PostMapping("/{ispb}/transfer/status")
    public void transferStatus(@PathVariable String ispb, @RequestBody FIToFIPaymentStatusReport statusReport) {
        var status = statusReportMapper.fromRegulatoryReport(statusReport);
        paymentTransactionProcessorUseCase.processStatusBatch(ispb, status);
    }

    @GetMapping("/{ispb}/messages")
    public SpiNotification getMessages(@PathVariable String ispb) {
        return notificationUseCase.getNotifications(ispb);
    }
}
