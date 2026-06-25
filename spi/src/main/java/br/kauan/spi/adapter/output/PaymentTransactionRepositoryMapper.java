package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import org.springframework.stereotype.Component;

@Component
public class PaymentTransactionRepositoryMapper {

    public PaymentTransactionEntity toEntity(PaymentTransactionCommand transaction, PaymentStatus status) {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId(transaction.getPaymentId());
        entity.setAmountCents(transaction.getAmountCents());
        entity.setCurrency(transaction.getCurrency());
        entity.setDescription(transaction.getDescription());
        entity.setStatus(status.name());

        var sender = transaction.getSender();

        // Map sender fields
        if (sender != null) {
            entity.setSenderName(sender.getName());
            entity.setSenderTaxId(sender.getTaxId());
            entity.setSenderPixKey(sender.getPixKey());

            if (sender.getAccount() != null) {
                entity.setSenderAccountNumber(sender.getAccount().getNumber());
                entity.setSenderAccountBranch(sender.getAccount().getBranch());
                entity.setSenderAccountType(sender.getAccount().getType().name());
                entity.setSenderBankCode(sender.getAccount().getBankCode());
            }
        }

        var receiver = transaction.getReceiver();

        // Map receiver fields
        if (receiver != null) {
            entity.setReceiverName(receiver.getName());
            entity.setReceiverTaxId(receiver.getTaxId());
            entity.setReceiverPixKey(receiver.getPixKey());

            if (receiver.getAccount() != null) {
                entity.setReceiverAccountNumber(receiver.getAccount().getNumber());
                entity.setReceiverAccountBranch(receiver.getAccount().getBranch());
                entity.setReceiverAccountType(receiver.getAccount().getType().name());
                entity.setReceiverBankCode(receiver.getAccount().getBankCode());
            }
        }

        return entity;
    }

    public PaymentTransactionCommand toDomain(PaymentTransactionEntity entity) {
        return PaymentTransactionCommand.builder()
                .paymentId(entity.getPaymentId())
                .amountCents(entity.getAmountCents() == null ? 0L : entity.getAmountCents())
                .currency(entity.getCurrency())
                .description(entity.getDescription())
                .sender(getSender(entity))
                .receiver(getReceiver(entity))
                .build();
    }

    public static Party getSender(PaymentTransactionEntity entity) {
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

    public static Party getReceiver(PaymentTransactionEntity entity) {
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

    private static BankAccount getSenderAccount(PaymentTransactionEntity entity) {
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

    private static BankAccount getReceiverAccount(PaymentTransactionEntity entity) {
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
