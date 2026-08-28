package br.kauan.spi.adapter.input.kafka.internal;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Component;

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

    public IncomingStatusReportCommand toStatusReport(PaymentStatusReport report) {
        return new IncomingStatusReportCommand(
                report.getPaymentId(),
                toDomainOutcome(report.getStatus()),
                report.getReasonsList().stream()
                        .map(reason -> StatusReasonCode.of(reason.getCode()))
                        .toList()
        );
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

    private StatusReportOutcome toDomainOutcome(br.kauan.pix.internal.v1.PaymentStatus status) {
        return switch (status) {
            case ACCEPTED_IN_PROCESS -> StatusReportOutcome.ACCEPTED;
            case REJECTED -> StatusReportOutcome.REJECTED;
            case PAYMENT_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("Unsupported internal payment status: " + status);
        };
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
