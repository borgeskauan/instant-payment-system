package br.kauan.paymentserviceprovider.state;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
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
public class PaymentStore {

    private final Map<String, PaymentTransaction> payments = new ConcurrentHashMap<>();
    private final Set<FinalStatusKey> appliedFinalStatuses = ConcurrentHashMap.newKeySet();
    private final Set<FinalStatusKey> claimedFinalStatuses = ConcurrentHashMap.newKeySet();

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

    public synchronized void saveAll(Collection<PaymentTransaction> transactions) {
        for (PaymentTransaction transaction : transactions) {
            payments.put(transaction.getPaymentId(), transaction);
        }
    }

    public synchronized IncomingPaymentClassification storeAndClassifyIncoming(Collection<PaymentTransaction> transactions) {
        Map<String, List<PaymentTransaction>> recordsByPaymentId = new LinkedHashMap<>();
        for (PaymentTransaction transaction : transactions) {
            recordsByPaymentId.computeIfAbsent(transaction.getPaymentId(), ignored -> new ArrayList<>()).add(transaction);
        }

        List<PaymentTransaction> acceptedPayments = new ArrayList<>();
        List<PaymentTransaction> divergentPayments = new ArrayList<>();
        for (List<PaymentTransaction> samePaymentIdRecords : recordsByPaymentId.values()) {
            PaymentTransaction first = samePaymentIdRecords.getFirst();
            if (containsDivergentRecords(first, samePaymentIdRecords)) {
                divergentPayments.addAll(samePaymentIdRecords);
                continue;
            }

            PaymentTransaction existing = payments.get(first.getPaymentId());
            if (existing == null) {
                payments.put(first.getPaymentId(), first);
                acceptedPayments.add(first);
            } else if (sameBusinessContent(existing, first)) {
                acceptedPayments.add(first);
            } else {
                divergentPayments.addAll(samePaymentIdRecords);
            }
        }

        return new IncomingPaymentClassification(acceptedPayments, divergentPayments);
    }

    public synchronized boolean claimFinalStatus(String paymentId, PaymentStatus status) {
        FinalStatusKey key = new FinalStatusKey(paymentId, status);
        if (appliedFinalStatuses.contains(key) || claimedFinalStatuses.contains(key)) {
            return false;
        }
        claimedFinalStatuses.add(key);
        return true;
    }

    public synchronized void markFinalStatusApplied(String paymentId, PaymentStatus status) {
        FinalStatusKey key = new FinalStatusKey(paymentId, status);
        claimedFinalStatuses.remove(key);
        appliedFinalStatuses.add(key);
    }

    public synchronized void releaseFinalStatusClaim(String paymentId, PaymentStatus status) {
        claimedFinalStatuses.remove(new FinalStatusKey(paymentId, status));
    }

    private boolean containsDivergentRecords(PaymentTransaction first, List<PaymentTransaction> records) {
        return records.stream().anyMatch(transaction -> !sameBusinessContent(first, transaction));
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
        return left == null || right == null ? Objects.equals(left, right) : left.compareTo(right) == 0;
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
        return Objects.equals(left.getId(), right.getId()) && Objects.equals(left.getType(), right.getType());
    }

    private record FinalStatusKey(String paymentId, PaymentStatus status) {
    }
}
