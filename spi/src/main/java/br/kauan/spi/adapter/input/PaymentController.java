package br.kauan.spi.adapter.input;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.port.input.PaymentTransactionUseCase;
import br.kauan.spi.port.input.StatusReportUseCase;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;

    private final PaymentTransactionUseCase paymentTransactionUseCase;
    private final StatusReportUseCase statusReportUseCase;

    public PaymentController(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper
//            PaymentTransactionUseCase paymentTransactionUseCase,
//            StatusReportUseCase statusReportUseCase
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
//        this.paymentTransactionUseCase = paymentTransactionUseCase;
//        this.statusReportUseCase = statusReportUseCase;
        this.paymentTransactionUseCase = null;
        this.statusReportUseCase = null;
    }

    @PostMapping("/{ispb}/transfer")
    public void transfer(@PathVariable String ispb, @RequestBody FIToFICustomerCreditTransfer request) {
        var transaction = paymentTransactionMapper.fromRegulatoryRequest(request);
        paymentTransactionUseCase.processTransaction(ispb, transaction);
    }

    @PostMapping("/{ispb}/transfer/status")
    public void transferStatus(@PathVariable String ispb, @RequestBody FIToFIPaymentStatusReport statusReport) {
        var status = statusReportMapper.fromRegulatoryReport(statusReport);
        statusReportUseCase.processStatusReport(ispb, status);
    }
}
