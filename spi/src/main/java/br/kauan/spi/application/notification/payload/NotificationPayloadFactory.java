package br.kauan.spi.application.notification.payload;

import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.status.NotificationStatusItem;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NotificationPayloadFactory {

    public Map<String, Object> paymentNotification(
            String messageId,
            List<PaymentTransactionCommand> transactions
    ) {
        return orderedMap(
                "GrpHdr", groupHeader(messageId, transactions.size()),
                "CdtTrfTxInf", paymentItems(transactions)
        );
    }

    public Map<String, Object> statusNotification(
            String messageId,
            List<NotificationStatusItem> statusReports
    ) {
        return orderedMap(
                "GrpHdr", groupHeader(messageId, statusReports.size()),
                "TxInfAndSts", statusItems(statusReports)
        );
    }

    private Map<String, Object> groupHeader(String messageId, int totalTransactions) {
        return orderedMap(
                "MsgId", messageId,
                "CreDtTm", Instant.now().atOffset(ZoneOffset.UTC),
                "NbOfTxs", BigInteger.valueOf(totalTransactions)
        );
    }

    private List<Map<String, Object>> paymentItems(List<PaymentTransactionCommand> transactions) {
        var items = new ArrayList<Map<String, Object>>(transactions.size());
        for (PaymentTransactionCommand transaction : transactions) {
            items.add(paymentItem(transaction));
        }
        return items;
    }

    private Map<String, Object> paymentItem(PaymentTransactionCommand transaction) {
        return orderedMap(
                "PmtId", orderedMap("EndToEndId", transaction.getPaymentId()),
                "IntrBkSttlmAmt", orderedMap(
                        "value", Money.toDecimal(transaction.getAmountCents()),
                        "Ccy", "BRL"
                ),
                "Dbtr", party(transaction.getSender()),
                "DbtrAcct", account(transaction.getSender()),
                "DbtrAgt", financialInstitution(transaction.getSender().getAccount()),
                "CdtrAgt", financialInstitution(transaction.getReceiver().getAccount()),
                "Cdtr", party(transaction.getReceiver()),
                "CdtrAcct", account(transaction.getReceiver()),
                "RmtInf", orderedMap("Ustrd", transaction.getDescription())
        );
    }

    private Map<String, Object> party(Party party) {
        return orderedMap(
                "Nm", party.getName(),
                "Id", orderedMap(
                        "PrvtId", orderedMap(
                                "Othr", orderedMap("Id", party.getTaxId())
                        )
                )
        );
    }

    private Map<String, Object> account(Party party) {
        BankAccount account = party.getAccount();
        return orderedMap(
                "Id", orderedMap(
                        "Othr", orderedMap(
                                "Id", account.getNumber(),
                                "Issr", account.getBranch()
                        )
                ),
                "Tp", orderedMap("Cd", accountType(account.getType())),
                "Prxy", orderedMap("Id", party.getPixKey())
        );
    }

    private Map<String, Object> financialInstitution(BankAccount account) {
        return orderedMap(
                "FinInstnId", orderedMap(
                        "ClrSysMmbId", orderedMap("MmbId", account.getBankCode())
                )
        );
    }

    private List<Map<String, Object>> statusItems(List<NotificationStatusItem> statusReports) {
        var items = new ArrayList<Map<String, Object>>(statusReports.size());
        for (NotificationStatusItem statusReport : statusReports) {
            items.add(statusItem(statusReport));
        }
        return items;
    }

    private Map<String, Object> statusItem(NotificationStatusItem statusReport) {
        return orderedMap(
                "OrgnlEndToEndId", statusReport.originalPaymentId(),
                "TxSts", statusReport.status().name(),
                "StsRsnInf", reasons(statusReport.reasonCodes())
        );
    }

    private List<Map<String, Object>> reasons(List<StatusReasonCode> reasons) {
        if (reasons.isEmpty()) {
            return List.of();
        }

        var items = new ArrayList<Map<String, Object>>(reasons.size());
        for (StatusReasonCode reason : reasons) {
            items.add(orderedMap(
                    "Rsn", orderedMap("Cd", reason.value())
            ));
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

    private Map<String, Object> orderedMap(Object... entries) {
        var map = new LinkedHashMap<String, Object>(entries.length / 2);
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
