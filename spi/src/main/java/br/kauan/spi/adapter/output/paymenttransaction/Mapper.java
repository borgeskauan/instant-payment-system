package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Component;

@Component
public class Mapper {

    public PaymentTransactionCommand toDomain(Entity entity) {
        return PaymentTransactionCommand.builder()
                .paymentId(entity.getPaymentId())
                .amountCents(entity.getAmountCents() == null ? 0L : entity.getAmountCents())
                .currency(entity.getCurrency())
                .description(entity.getDescription())
                .sender(getSender(entity))
                .receiver(getReceiver(entity))
                .build();
    }

    public static Party getSender(Entity entity) {
        if (entity.getSenderName() == null && entity.getSenderBankCode() == null) {
            return null;
        }

        return Party.builder()
                .name(entity.getSenderName())
                .taxId(entity.getSenderTaxId())
                .pixKey(entity.getSenderPixKey())
                .account(getSenderAccount(entity))
                .build();
    }

    public static Party getReceiver(Entity entity) {
        if (entity.getReceiverName() == null && entity.getReceiverBankCode() == null) {
            return null;
        }

        return Party.builder()
                .name(entity.getReceiverName())
                .taxId(entity.getReceiverTaxId())
                .pixKey(entity.getReceiverPixKey())
                .account(getReceiverAccount(entity))
                .build();
    }

    private static BankAccount getSenderAccount(Entity entity) {
        if (entity.getSenderAccountNumber() == null && entity.getSenderBankCode() == null) {
            return null;
        }

        return BankAccount.builder()
                .number(entity.getSenderAccountNumber())
                .branch(entity.getSenderAccountBranch())
                .type(entity.getSenderAccountType() == null
                        ? null
                        : BankAccountType.fromString(entity.getSenderAccountType()))
                .bankCode(entity.getSenderBankCode())
                .build();
    }

    private static BankAccount getReceiverAccount(Entity entity) {
        if (entity.getReceiverAccountNumber() == null && entity.getReceiverBankCode() == null) {
            return null;
        }

        return BankAccount.builder()
                .number(entity.getReceiverAccountNumber())
                .branch(entity.getReceiverAccountBranch())
                .type(entity.getReceiverAccountType() == null
                        ? null
                        : BankAccountType.fromString(entity.getReceiverAccountType()))
                .bankCode(entity.getReceiverBankCode())
                .build();
    }
}
