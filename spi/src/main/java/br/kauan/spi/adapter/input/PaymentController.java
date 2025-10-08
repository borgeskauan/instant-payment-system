package br.kauan.spi.adapter.input;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

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
    public Mono<Void> transfer(@PathVariable String ispb
//                               @RequestBody FIToFICustomerCreditTransfer request
    ) {
        return Mono.empty();
//        var transaction = paymentTransactionMapper.fromRegulatoryRequest(request);
//        paymentTransactionProcessorUseCase.processTransactionBatch(ispb, transaction);
    }

    @PostMapping("/{ispb}/transfer/status")
    public Mono<Void> transferStatus(@PathVariable String ispb
//                                     @RequestBody FIToFIPaymentStatusReport statusReport
    ) {
        return Mono.empty();
//        var status = statusReportMapper.fromRegulatoryReport(statusReport);
//        paymentTransactionProcessorUseCase.processStatusBatch(ispb, status);
    }

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
