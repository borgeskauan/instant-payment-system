package br.kauan.spi.application.notification.payload;

import br.kauan.spi.application.notification.NotificationException;
import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.status.NotificationStatusItem;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Component
public class NotificationPayloadFactory {

    private final ObjectMapper objectMapper;

    public NotificationPayloadFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] paymentNotification(
            String messageId,
            List<PaymentTransactionCommand> transactions
    ) {
        return serialize(new PaymentNotification(
                groupHeader(messageId, transactions.size()),
                paymentItems(transactions)
        ));
    }

    public byte[] statusNotification(
            String messageId,
            List<NotificationStatusItem> statusReports
    ) {
        return serialize(new StatusNotification(
                groupHeader(messageId, statusReports.size()),
                statusItems(statusReports)
        ));
    }

    private GroupHeader groupHeader(String messageId, int totalTransactions) {
        return new GroupHeader(
                messageId,
                Instant.now().atOffset(ZoneOffset.UTC),
                BigInteger.valueOf(totalTransactions)
        );
    }

    private List<PaymentItem> paymentItems(List<PaymentTransactionCommand> transactions) {
        var items = new ArrayList<PaymentItem>(transactions.size());
        for (PaymentTransactionCommand transaction : transactions) {
            items.add(paymentItem(transaction));
        }
        return items;
    }

    private PaymentItem paymentItem(PaymentTransactionCommand transaction) {
        return new PaymentItem(
                new PaymentIdentification(transaction.getPaymentId()),
                new Amount(Money.toDecimal(transaction.getAmountCents()), "BRL"),
                party(transaction.getSender()),
                account(transaction.getSender()),
                financialInstitution(transaction.getSender().getAccount()),
                financialInstitution(transaction.getReceiver().getAccount()),
                party(transaction.getReceiver()),
                account(transaction.getReceiver()),
                new RemittanceInformation(transaction.getDescription())
        );
    }

    private PaymentParty party(Party party) {
        return new PaymentParty(
                party.getName(),
                new PartyIdentification(
                        new PrivateIdentification(
                                new OtherIdentification(party.getTaxId())
                        )
                )
        );
    }

    private PaymentAccount account(Party party) {
        BankAccount account = party.getAccount();
        return new PaymentAccount(
                new AccountIdentification(
                        new OtherAccountIdentification(
                                account.getNumber(),
                                account.getBranch()
                        )
                ),
                new AccountType(accountType(account.getType())),
                new ProxyIdentification(party.getPixKey())
        );
    }

    private FinancialAgent financialInstitution(BankAccount account) {
        return new FinancialAgent(
                new FinancialInstitutionIdentification(
                        new ClearingSystemMemberIdentification(account.getBankCode())
                )
        );
    }

    private List<StatusItem> statusItems(List<NotificationStatusItem> statusReports) {
        var items = new ArrayList<StatusItem>(statusReports.size());
        for (NotificationStatusItem statusReport : statusReports) {
            items.add(statusItem(statusReport));
        }
        return items;
    }

    private StatusItem statusItem(NotificationStatusItem statusReport) {
        return new StatusItem(
                statusReport.originalPaymentId(),
                statusReport.status().name(),
                reasons(statusReport.reasonCodes())
        );
    }

    private List<StatusReasonInformation> reasons(List<StatusReasonCode> reasons) {
        if (reasons.isEmpty()) {
            return List.of();
        }

        var items = new ArrayList<StatusReasonInformation>(reasons.size());
        for (StatusReasonCode reason : reasons) {
            items.add(new StatusReasonInformation(new StatusReason(reason.value())));
        }
        return items;
    }

    private String accountType(BankAccountType type) {
        return switch (type) {
            case CHECKING -> "CACC";
            case SAVINGS -> "SVGS";
            case SALARY -> "SLRY";
            case PAYMENT -> "TRAN";
        };
    }

    private byte[] serialize(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new NotificationException(
                    "Failed to serialize notification payload of type " + payload.getClass().getSimpleName(),
                    exception
            );
        }
    }

    private record PaymentNotification(GroupHeader GrpHdr, List<PaymentItem> CdtTrfTxInf) {
    }

    private record StatusNotification(GroupHeader GrpHdr, List<StatusItem> TxInfAndSts) {
    }

    private record GroupHeader(String MsgId, OffsetDateTime CreDtTm, BigInteger NbOfTxs) {
    }

    private record PaymentItem(
            PaymentIdentification PmtId,
            Amount IntrBkSttlmAmt,
            PaymentParty Dbtr,
            PaymentAccount DbtrAcct,
            FinancialAgent DbtrAgt,
            FinancialAgent CdtrAgt,
            PaymentParty Cdtr,
            PaymentAccount CdtrAcct,
            RemittanceInformation RmtInf
    ) {
    }

    private record PaymentIdentification(String EndToEndId) {
    }

    private record Amount(BigDecimal value, String Ccy) {
    }

    private record PaymentParty(String Nm, PartyIdentification Id) {
    }

    private record PartyIdentification(PrivateIdentification PrvtId) {
    }

    private record PrivateIdentification(OtherIdentification Othr) {
    }

    private record OtherIdentification(String Id) {
    }

    private record PaymentAccount(AccountIdentification Id, AccountType Tp, ProxyIdentification Prxy) {
    }

    private record AccountIdentification(OtherAccountIdentification Othr) {
    }

    private record OtherAccountIdentification(String Id, String Issr) {
    }

    private record AccountType(String Cd) {
    }

    private record ProxyIdentification(String Id) {
    }

    private record FinancialAgent(FinancialInstitutionIdentification FinInstnId) {
    }

    private record FinancialInstitutionIdentification(ClearingSystemMemberIdentification ClrSysMmbId) {
    }

    private record ClearingSystemMemberIdentification(String MmbId) {
    }

    private record RemittanceInformation(String Ustrd) {
    }

    private record StatusItem(
            String OrgnlEndToEndId,
            String TxSts,
            List<StatusReasonInformation> StsRsnInf
    ) {
    }

    private record StatusReasonInformation(StatusReason Rsn) {
    }

    private record StatusReason(String Cd) {
    }
}
