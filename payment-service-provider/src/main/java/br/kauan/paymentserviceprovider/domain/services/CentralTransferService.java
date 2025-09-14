package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.CentralTransferRestClient;
import br.kauan.paymentserviceprovider.adapter.output.InfiniteLoopService;
import br.kauan.paymentserviceprovider.adapter.output.pacs.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class CentralTransferService {

    private final CentralTransferRestClient transferRestClient;
    private final PaymentTransactionMapper paymentTransactionMapper;

    private final InfiniteLoopService infiniteLoopService;

    public CentralTransferService(CentralTransferRestClient transferRestClient, PaymentTransactionMapper paymentTransactionMapper, InfiniteLoopService infiniteLoopService) {
        this.transferRestClient = transferRestClient;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.infiniteLoopService = infiniteLoopService;
    }

    @PostConstruct
    public void init() {
        infiniteLoopService.startLoop(this::receiveNotifications);
    }

    public void requestTransfer(Party sender, Party receiver, BigDecimal amount) {
        var paymentBatch = buildPaymentBatch(sender, receiver, amount);

        var regulatoryBatch = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);

        transferRestClient.requestTransfer(GlobalVariables.getBankCode(), regulatoryBatch);
    }

    private PaymentBatch buildPaymentBatch(Party sender, Party receiver, BigDecimal amount) {
        var paymentTransaction = PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(amount)
                .currency("BRL")
                .sender(sender)
                .receiver(receiver)
                .build();

        var batchDetails = BatchDetails.of(1);

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(List.of(paymentTransaction))
                .build();
    }

    private void receiveNotifications() {
        var notifications = transferRestClient.getMessages(GlobalVariables.getBankCode()).getContent();

        log.info("Received {} notifications from central transfer service", notifications.size());
        if (!notifications.isEmpty()) {
            log.info(notifications.toString());

        }
    }
}
