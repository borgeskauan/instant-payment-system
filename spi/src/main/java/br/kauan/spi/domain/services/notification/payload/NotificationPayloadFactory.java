package br.kauan.spi.domain.services.notification.payload;

import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.Reason;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
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
import java.util.UUID;

@Component
public class NotificationPayloadFactory {

    public Map<String, Object> paymentNotification(List<PaymentTransactionCommand> transactions) {
        return orderedMap(
                "GrpHdr", groupHeader(transactions.size()),
                "CdtTrfTxInf", paymentItems(transactions)
        );
    }

    public Map<String, Object> statusNotification(List<StatusReportCommand> statusReports) {
        return orderedMap(
                "GrpHdr", groupHeader(statusReports.size()),
                "TxInfAndSts", statusItems(statusReports)
        );
    }

    private Map<String, Object> groupHeader(int totalTransactions) {
        return orderedMap(
                "MsgId", UUID.randomUUID().toString(),
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

    private List<Map<String, Object>> statusItems(List<StatusReportCommand> statusReports) {
        var items = new ArrayList<Map<String, Object>>(statusReports.size());
        for (StatusReportCommand statusReport : statusReports) {
            items.add(statusItem(statusReport));
        }
        return items;
    }

    private Map<String, Object> statusItem(StatusReportCommand statusReport) {
        return orderedMap(
                "OrgnlEndToEndId", statusReport.getOriginalPaymentId(),
                "TxSts", paymentStatus(statusReport.getStatus()),
                "StsRsnInf", reasons(statusReport.getReasons())
        );
    }

    private List<Map<String, Object>> reasons(List<Reason> reasons) {
        if (reasons == null) {
            return List.of();
        }

        var items = new ArrayList<Map<String, Object>>(reasons.size());
        for (Reason reason : reasons) {
            items.add(orderedMap(
                    "Rsn", orderedMap("Cd", reasonCode(reason.getCode())),
                    "AddtlInf", reason.getDescriptions()
            ));
        }
        return items;
    }

    private String paymentStatus(PaymentStatus status) {
        return switch (status) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> "ACCC";
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> "ACSC";
            case ACCEPTED_IN_PROCESS -> "ACSP";
            case REJECTED -> "RJCT";
            case WAITING_ACCEPTANCE, ACCEPTED_AND_SETTLED ->
                    throw new IllegalArgumentException("No notification mapping for status: " + status);
        };
    }

    private String accountType(BankAccountType type) {
        return switch (type) {
            case CHECKING -> "CACC";
            case SAVINGS -> "SVGS";
            case SALARY -> "SLRY";
            case PAYMENT -> "TRAN";
        };
    }

    private String reasonCode(String code) {
        return code == null || code.isBlank() ? "AB03" : code;
    }

    private Map<String, Object> orderedMap(Object... entries) {
        var map = new LinkedHashMap<String, Object>(entries.length / 2);
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
