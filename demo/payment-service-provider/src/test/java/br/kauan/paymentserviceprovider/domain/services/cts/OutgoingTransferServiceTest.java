package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.dict.DictClient;
import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutgoingTransferServiceTest {

    private DictClient dictClient;
    private PaymentTransactionMapper paymentTransactionMapper;
    private CentralTransferSystemRestClient transferRestClient;
    private PaymentStore paymentStore;
    private OutgoingTransferService service;

    @BeforeEach
    void setUp() {
        dictClient = mock(DictClient.class);
        paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        transferRestClient = mock(CentralTransferSystemRestClient.class);
        paymentStore = new PaymentStore();

        var stateStore = new PspStateStore();
        var customer = Customer.builder()
                .id("customer-1")
                .name("Sender")
                .taxId("11111111111")
                .build();
        var senderAccount = BankAccount.builder()
                .id(BankAccountId.builder()
                        .bankCode("11111111")
                        .agencyNumber("1")
                        .accountNumber("100")
                        .build())
                .type(BankAccountType.PAYMENT)
                .build();
        stateStore.addCustomer(customer, CustomerBankAccount.builder()
                .customerId(customer.getId())
                .account(senderAccount)
                .balance(new BigDecimal("1000.00"))
                .build());

        service = new OutgoingTransferService(
                dictClient,
                stateStore,
                paymentStore,
                paymentTransactionMapper,
                transferRestClient,
                new ObjectMapper()
        );
    }

    @Test
    void resolvesThePixKeyAgainWhenExecutingTheReviewedTransfer() {
        Party previewReceiver = receiver("Preview Receiver", "200");
        Party executionReceiver = receiver("Execution Receiver", "201");
        when(dictClient.resolve("receiver-key"))
                .thenReturn(previewReceiver, executionReceiver);
        when(paymentTransactionMapper.toRegulatoryRequest(org.mockito.ArgumentMatchers.any()))
                .thenReturn(FIToFICustomerCreditTransfer.builder().build());

        assertThat(service.preview(new TransferPreviewRequest("receiver-key")).getReceiver())
                .isSameAs(previewReceiver);

        var result = service.execute(new TransferExecutionRequest(
                "customer-1",
                "receiver-key",
                new BigDecimal("25.50"),
                "Demo payment"
        ));

        ArgumentCaptor<PaymentTransaction> payment = ArgumentCaptor.captor();
        verify(paymentTransactionMapper).toRegulatoryRequest(payment.capture());
        verify(dictClient, times(2)).resolve("receiver-key");
        verify(transferRestClient).requestTransfer(org.mockito.ArgumentMatchers.any(byte[].class));
        assertThat(payment.getValue().getReceiver()).isSameAs(executionReceiver);
        assertThat(payment.getValue().getAmount()).isEqualByComparingTo("25.50");
        assertThat(paymentStore.findAllByIds(java.util.List.of(result.getTransferId())))
                .containsExactly(payment.getValue());
    }

    private Party receiver(String name, String accountNumber) {
        return Party.builder()
                .name(name)
                .taxId("22222222222")
                .pixKey("receiver-key")
                .account(BankAccount.builder()
                        .id(BankAccountId.builder()
                                .bankCode("22222222")
                                .agencyNumber("2")
                                .accountNumber(accountNumber)
                                .build())
                        .type(BankAccountType.PAYMENT)
                        .build())
                .build();
    }
}
