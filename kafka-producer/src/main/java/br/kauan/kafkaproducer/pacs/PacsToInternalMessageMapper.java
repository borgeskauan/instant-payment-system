package br.kauan.kafkaproducer.pacs;

import br.kauan.pix.internal.v1.BankAccount;
import br.kauan.pix.internal.v1.Party;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.pix.internal.v1.StatusReason;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class PacsToInternalMessageMapper {

    private final ObjectReader pacs008Reader;
    private final ObjectReader pacs002Reader;

    public PacsToInternalMessageMapper() {
        this(new ObjectMapper());
    }

    PacsToInternalMessageMapper(ObjectMapper objectMapper) {
        this.pacs008Reader = objectMapper.readerFor(Pacs008Envelope.class);
        this.pacs002Reader = objectMapper.readerFor(Pacs002Envelope.class);
    }

    public List<PaymentRequest> toPaymentRequests(byte[] payload) {
        try {
            Pacs008Envelope envelope = pacs008Reader.readValue(payload);
            List<Pacs008Transaction> transactions = requiredNonEmpty(envelope.transactions(), "PACS.008 transaction");
            return transactions.stream()
                    .map(this::toPaymentRequest)
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid PACS.008 payload", e);
        }
    }

    public List<PaymentStatusReport> toPaymentStatusReports(byte[] payload) {
        try {
            Pacs002Envelope envelope = pacs002Reader.readValue(payload);
            List<Pacs002Transaction> transactions = requiredNonEmpty(envelope.transactions(), "PACS.002 transaction");
            return transactions.stream()
                    .map(this::toPaymentStatusReport)
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid PACS.002 payload", e);
        }
    }

    private PaymentRequest toPaymentRequest(Pacs008Transaction transaction) {
        String paymentId = required(firstNonBlank(
                transaction.paymentIdentification().endToEndId(),
                transaction.paymentIdentification().endToEndID()), "payment id");

        return PaymentRequest.newBuilder()
                .setPaymentId(paymentId)
                .setAmountCents(toCents(transaction.amount().value()))
                .setCurrency(required(transaction.amount().currency(), "currency"))
                .setDescription(valueOrEmpty(transaction.remittanceInformation() == null
                        ? null
                        : transaction.remittanceInformation().additionalInformation()))
                .setSender(toParty(
                        transaction.debtor(),
                        transaction.debtorAccount(),
                        transaction.debtorFinancialInstitution()))
                .setReceiver(toParty(
                        transaction.creditor(),
                        transaction.creditorAccount(),
                        transaction.creditorFinancialInstitution()))
                .build();
    }

    private PaymentStatusReport toPaymentStatusReport(Pacs002Transaction transaction) {
        String paymentId = required(firstNonBlank(
                transaction.originalEndToEndId(),
                transaction.originalEndToEndID()), "original payment id");

        PaymentStatusReport.Builder builder = PaymentStatusReport.newBuilder()
                .setPaymentId(paymentId)
                .setStatus(toPaymentStatus(required(transaction.status(), "transaction status")));

        if (transaction.statusReasonInformations() != null) {
            for (Pacs002StatusReasonInformation reasonInformation : transaction.statusReasonInformations()) {
                builder.addReasons(StatusReason.newBuilder()
                        .setCode(valueOrEmpty(reasonInformation.reason() == null
                                || reasonInformation.reason().code() == null
                                ? null
                                : reasonInformation.reason().code()))
                        .setDescription(reasonInformation.additionalInformation() == null
                                || reasonInformation.additionalInformation().isEmpty()
                                ? ""
                                : reasonInformation.additionalInformation().getFirst())
                        .build());
            }
        }

        return builder.build();
    }

    private static Party toParty(PacsParty party, PacsAccount account, PacsAgent agent) {
        return Party.newBuilder()
                .setName(valueOrEmpty(party == null ? null : party.name()))
                .setTaxId(valueOrEmpty(party == null
                        || party.id() == null
                        || party.id().privateId() == null
                        || party.id().privateId().other() == null
                        ? null
                        : party.id().privateId().other().id()))
                .setPixKey(valueOrEmpty(account == null
                        || account.proxy() == null
                        ? null
                        : account.proxy().id()))
                .setAccount(toBankAccount(account, agent))
                .build();
    }

    private static BankAccount toBankAccount(PacsAccount account, PacsAgent agent) {
        PacsAccountOther accountId = account == null || account.idChoice() == null
                ? null
                : account.idChoice().other();
        return BankAccount.newBuilder()
                .setNumber(valueOrEmpty(accountId == null ? null : accountId.id()))
                .setBranch(valueOrEmpty(accountId == null ? null : accountId.issuer()))
                .setType(toInternalAccountType(account == null
                        || account.type() == null
                        ? null
                        : account.type().code()))
                .setIspb(valueOrEmpty(agent == null
                        || agent.financialInstitutionIdentification() == null
                        || agent.financialInstitutionIdentification().financialInstitutionIdentification() == null
                        ? null
                        : agent.financialInstitutionIdentification().financialInstitutionIdentification().memberId()))
                .build();
    }

    private static String toInternalAccountType(String externalType) {
        if (externalType == null || externalType.isBlank()) {
            return "";
        }
        return switch (externalType) {
            case "CACC" -> "CHECKING";
            case "SVGS" -> "SAVINGS";
            case "SLRY" -> "SALARY";
            case "TRAN" -> "PAYMENT";
            default -> externalType;
        };
    }

    private static PaymentStatus toPaymentStatus(String status) {
        return switch (status) {
            case "ACSP" -> PaymentStatus.ACCEPTED_IN_PROCESS;
            case "RJCT" -> PaymentStatus.REJECTED;
            default -> throw new IllegalArgumentException("Unsupported PACS.002 status: " + status);
        };
    }

    private static long toCents(BigDecimal amount) {
        return required(amount, "amount")
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    private static <T> List<T> requiredNonEmpty(List<T> values, String description) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Missing " + description);
        }
        return values;
    }

    private static <T> T required(T value, String description) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + description);
        }
        if (value instanceof String string && string.isBlank()) {
            throw new IllegalArgumentException("Missing " + description);
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pacs008Envelope(
            @JsonProperty("CdtTrfTxInf") List<Pacs008Transaction> transactions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pacs008Transaction(
            @JsonProperty("PmtId") PacsPaymentIdentification paymentIdentification,
            @JsonProperty("IntrBkSttlmAmt") PacsAmount amount,
            @JsonProperty("Dbtr") PacsParty debtor,
            @JsonProperty("DbtrAcct") PacsAccount debtorAccount,
            @JsonProperty("DbtrAgt") PacsAgent debtorFinancialInstitution,
            @JsonProperty("Cdtr") PacsParty creditor,
            @JsonProperty("CdtrAcct") PacsAccount creditorAccount,
            @JsonProperty("CdtrAgt") PacsAgent creditorFinancialInstitution,
            @JsonProperty("RmtInf") PacsRemittanceInformation remittanceInformation
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsPaymentIdentification(
            @JsonProperty("EndToEndId") String endToEndId,
            @JsonProperty("EndToEndID") String endToEndID
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsAmount(
            @JsonProperty("value") BigDecimal value,
            @JsonProperty("Ccy") String currency
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsParty(
            @JsonProperty("Nm") String name,
            @JsonProperty("Id") PacsPartyId id
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsPartyId(
            @JsonProperty("PrvtId") PacsPrivateId privateId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsPrivateId(
            @JsonProperty("Othr") PacsGenericId other
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsGenericId(
            @JsonProperty("Id") String id
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsAccount(
            @JsonProperty("Id") PacsAccountId idChoice,
            @JsonProperty("Tp") PacsAccountType type,
            @JsonProperty("Prxy") PacsProxy proxy
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsAccountId(
            @JsonProperty("Othr") PacsAccountOther other
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsAccountOther(
            @JsonProperty("Id") String id,
            @JsonProperty("Issr") String issuer
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsAccountType(
            @JsonProperty("Cd") String code
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsProxy(
            @JsonProperty("Id") String id
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsAgent(
            @JsonProperty("FinInstnId") PacsFinancialInstitution financialInstitutionIdentification
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsFinancialInstitution(
            @JsonProperty("ClrSysMmbId") PacsClearingSystemMember financialInstitutionIdentification
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsClearingSystemMember(
            @JsonProperty("MmbId") String memberId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PacsRemittanceInformation(
            @JsonProperty("Ustrd") String additionalInformation
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pacs002Envelope(
            @JsonProperty("TxInfAndSts") List<Pacs002Transaction> transactions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pacs002Transaction(
            @JsonProperty("OrgnlEndToEndId") String originalEndToEndId,
            @JsonProperty("OrgnlEndToEndID") String originalEndToEndID,
            @JsonProperty("TxSts") String status,
            @JsonProperty("StsRsnInf") List<Pacs002StatusReasonInformation> statusReasonInformations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pacs002StatusReasonInformation(
            @JsonProperty("Rsn") Pacs002StatusReason reason,
            @JsonProperty("AddtlInf") List<String> additionalInformation
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pacs002StatusReason(
            @JsonProperty("Cd") String code
    ) {
    }
}
