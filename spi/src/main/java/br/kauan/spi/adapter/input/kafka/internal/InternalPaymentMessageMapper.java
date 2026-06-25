package br.kauan.spi.adapter.input.kafka.internal;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.Reason;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InternalPaymentMessageMapper {

    public PaymentTransactionCommand toPaymentTransaction(PaymentRequest request) {
        return PaymentTransactionCommand.builder()
                .paymentId(request.getPaymentId())
                .amountCents(request.getAmountCents())
                .currency(emptyToNull(request.getCurrency()))
                .description(emptyToNull(request.getDescription()))
                .sender(toParty(request.getSender()))
                .receiver(toParty(request.getReceiver()))
                .build();
    }

    public StatusReportCommand toStatusReport(PaymentStatusReport report) {
        return StatusReportCommand.builder()
                .originalPaymentId(report.getPaymentId())
                .status(toDomainStatus(report.getStatus()))
                .reasons(report.getReasonsList().stream()
                        .map(this::toReason)
                        .toList())
                .build();
    }

    private Party toParty(br.kauan.pix.internal.v1.Party party) {
        return Party.builder()
                .name(emptyToNull(party.getName()))
                .taxId(emptyToNull(party.getTaxId()))
                .pixKey(emptyToNull(party.getPixKey()))
                .account(toBankAccount(party.getAccount()))
                .build();
    }

    private BankAccount toBankAccount(br.kauan.pix.internal.v1.BankAccount account) {
        return BankAccount.builder()
                .number(emptyToNull(account.getNumber()))
                .branch(emptyToNull(account.getBranch()))
                .type(account.getType().isBlank() ? null : BankAccountType.fromString(account.getType()))
                .bankCode(emptyToNull(account.getIspb()))
                .build();
    }

    private Reason toReason(br.kauan.pix.internal.v1.StatusReason reason) {
        return Reason.builder()
                .code(emptyToNull(reason.getCode()))
                .descriptions(reason.getDescription().isBlank()
                        ? List.of()
                        : List.of(reason.getDescription()))
                .build();
    }

    private PaymentStatus toDomainStatus(br.kauan.pix.internal.v1.PaymentStatus status) {
        return switch (status) {
            case ACCEPTED_IN_PROCESS -> PaymentStatus.ACCEPTED_IN_PROCESS;
            case REJECTED -> PaymentStatus.REJECTED;
            case PAYMENT_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Unsupported internal payment status: " + status);
        };
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
