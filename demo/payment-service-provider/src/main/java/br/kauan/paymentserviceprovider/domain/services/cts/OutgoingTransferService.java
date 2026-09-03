package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.dict.DictClient;
import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class OutgoingTransferService {

    private static final String CURRENCY_BRL = "BRL";

    private final DictClient dictClient;
    private final PspStateStore stateStore;
    private final PaymentStore paymentStore;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final CentralTransferSystemRestClient transferRestClient;
    private final ObjectMapper objectMapper;

    public OutgoingTransferService(
            DictClient dictClient,
            PspStateStore stateStore,
            PaymentStore paymentStore,
            PaymentTransactionMapper paymentTransactionMapper,
            CentralTransferSystemRestClient transferRestClient,
            ObjectMapper objectMapper
    ) {
        this.dictClient = dictClient;
        this.stateStore = stateStore;
        this.paymentStore = paymentStore;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.transferRestClient = transferRestClient;
        this.objectMapper = objectMapper;
    }

    public TransferPreviewDetails preview(TransferPreviewRequest request) {
        Party receiver = dictClient.resolve(request.receiverPixKey());
        return TransferPreviewDetails.builder().receiver(receiver).build();
    }

    public TransferDetails execute(TransferExecutionRequest request) {
        var customer = stateStore.findCustomerById(request.senderCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Sender customer not found"));
        var account = stateStore.findAccountByCustomerId(customer.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sender account not found"));
        var sender = Party.builder()
                .name(customer.getName())
                .taxId(customer.getTaxId())
                .account(account.getAccount())
                .build();
        var receiver = dictClient.resolve(request.receiverPixKey());
        var payment = PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(request.amount())
                .currency(CURRENCY_BRL)
                .sender(sender)
                .receiver(receiver)
                .description(request.description())
                .build();

        paymentStore.saveAll(List.of(payment));
        submit(payment);
        return TransferDetails.builder().transferId(payment.getPaymentId()).build();
    }

    private void submit(PaymentTransaction payment) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(paymentTransactionMapper.toRegulatoryRequest(payment));
            transferRestClient.requestTransfer(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to submit transfer", failure);
        }
    }
}
