package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.dto.PaymentSummary;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentDirection;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import br.kauan.paymentserviceprovider.state.StoredPayment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class PaymentHistoryService {

    private final PspStateStore stateStore;
    private final PaymentStore paymentStore;

    public PaymentHistoryService(PspStateStore stateStore, PaymentStore paymentStore) {
        this.stateStore = stateStore;
        this.paymentStore = paymentStore;
    }

    public List<PaymentSummary> findByCustomerId(String customerId) {
        BankAccountId accountId = stateStore.findAccountByCustomerId(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer has no bank account."))
                .getAccount()
                .getId();

        return paymentStore.findAllByAccountId(accountId).stream()
                .map(payment -> summarize(payment, accountId))
                .toList();
    }

    private PaymentSummary summarize(StoredPayment stored, BankAccountId localAccountId) {
        boolean outgoing = Objects.equals(stored.payment().getSender().getAccount().getId(), localAccountId);
        Party counterparty = outgoing ? stored.payment().getReceiver() : stored.payment().getSender();
        return new PaymentSummary(
                stored.payment().getPaymentId(),
                outgoing ? PaymentDirection.OUTGOING : PaymentDirection.INCOMING,
                new PaymentSummary.Counterparty(counterparty.getName(), counterparty.getPixKey()),
                stored.payment().getAmount(),
                stored.payment().getCurrency(),
                stored.status(),
                stored.createdAt()
        );
    }
}
