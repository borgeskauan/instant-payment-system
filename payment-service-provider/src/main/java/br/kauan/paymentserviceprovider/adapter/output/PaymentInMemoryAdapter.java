package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.IncomingPaymentRequestClassification;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class PaymentInMemoryAdapter implements PaymentRepository {

    private final Map<String, PaymentTransaction> payments = new ConcurrentHashMap<>();
    private final Set<FinalStatusApplicationKey> appliedFinalStatuses = ConcurrentHashMap.newKeySet();
    private final Set<FinalStatusApplicationKey> claimedFinalStatuses = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized List<PaymentTransaction> findAllByIds(Collection<String> paymentIds) {
        List<PaymentTransaction> foundPayments = new ArrayList<>(paymentIds.size());
        for (String paymentId : paymentIds) {
            PaymentTransaction payment = payments.get(paymentId);
            if (payment != null) {
                foundPayments.add(payment);
            }
        }
        return foundPayments;
    }

    @Override
    public synchronized void saveAll(Collection<PaymentTransaction> transactions) {
        for (PaymentTransaction transaction : transactions) {
            payments.put(transaction.getPaymentId(), transaction);
        }
    }

    @Override
    public synchronized IncomingPaymentRequestClassification storeAndClassifyIncomingRequests(
            Collection<PaymentTransaction> transactions
    ) {
        Map<String, List<PaymentTransaction>> recordsByPaymentId = new LinkedHashMap<>();
        for (PaymentTransaction transaction : transactions) {
            recordsByPaymentId
                    .computeIfAbsent(transaction.getPaymentId(), ignored -> new ArrayList<>())
                    .add(transaction);
        }

        List<PaymentTransaction> acceptedPaymentRequests = new ArrayList<>();
        List<PaymentTransaction> divergentPaymentRequests = new ArrayList<>();
        for (List<PaymentTransaction> samePaymentIdRecords : recordsByPaymentId.values()) {
            PaymentTransaction first = samePaymentIdRecords.getFirst();
            if (containsDivergentBatchRecords(first, samePaymentIdRecords)) {
                divergentPaymentRequests.addAll(samePaymentIdRecords);
                continue;
            }

            PaymentTransaction existing = payments.get(first.getPaymentId());
            if (existing == null) {
                payments.put(first.getPaymentId(), first);
                acceptedPaymentRequests.add(first);
                continue;
            }

            if (sameBusinessContent(existing, first)) {
                acceptedPaymentRequests.add(first);
            } else {
                divergentPaymentRequests.addAll(samePaymentIdRecords);
            }
        }

        return new IncomingPaymentRequestClassification(acceptedPaymentRequests, divergentPaymentRequests);
    }

    @Override
    public synchronized boolean claimFinalStatusApplication(String paymentId, PaymentStatus status) {
        FinalStatusApplicationKey key = new FinalStatusApplicationKey(paymentId, status);
        if (appliedFinalStatuses.contains(key) || claimedFinalStatuses.contains(key)) {
            return false;
        }

        claimedFinalStatuses.add(key);
        return true;
    }

    @Override
    public synchronized void markFinalStatusApplied(String paymentId, PaymentStatus status) {
        FinalStatusApplicationKey key = new FinalStatusApplicationKey(paymentId, status);
        claimedFinalStatuses.remove(key);
        appliedFinalStatuses.add(key);
    }

    @Override
    public synchronized void releaseFinalStatusApplicationClaim(String paymentId, PaymentStatus status) {
        claimedFinalStatuses.remove(new FinalStatusApplicationKey(paymentId, status));
    }

    private boolean containsDivergentBatchRecords(
            PaymentTransaction first,
            List<PaymentTransaction> samePaymentIdRecords
    ) {
        for (PaymentTransaction transaction : samePaymentIdRecords) {
            if (!sameBusinessContent(first, transaction)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameBusinessContent(PaymentTransaction left, PaymentTransaction right) {
        return Objects.equals(left.getPaymentId(), right.getPaymentId())
                && sameAmount(left.getAmount(), right.getAmount())
                && Objects.equals(left.getCurrency(), right.getCurrency())
                && Objects.equals(left.getDescription(), right.getDescription())
                && sameParty(left.getSender(), right.getSender())
                && sameParty(left.getReceiver(), right.getReceiver());
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }

        return left.compareTo(right) == 0;
    }

    private boolean sameParty(Party left, Party right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }

        return Objects.equals(left.getName(), right.getName())
                && Objects.equals(left.getTaxId(), right.getTaxId())
                && Objects.equals(left.getPixKey(), right.getPixKey())
                && sameAccount(left.getAccount(), right.getAccount());
    }

    private boolean sameAccount(BankAccount left, BankAccount right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }

        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getType(), right.getType());
    }

    private record FinalStatusApplicationKey(String paymentId, PaymentStatus status) {
    }
}
