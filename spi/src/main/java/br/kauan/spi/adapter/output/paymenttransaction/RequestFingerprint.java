package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestFingerprint {

    public static final String VERSION = "v1";

    private RequestFingerprint() {
    }

    public static String from(PaymentTransactionCommand command) {
        return sha256(canonicalPayload(command));
    }

    private static String canonicalPayload(PaymentTransactionCommand command) {
        StringBuilder payload = new StringBuilder();
        field(payload, "paymentId", command.getPaymentId());
        field(payload, "amountCents", Long.toString(command.getAmountCents()));
        field(payload, "currency", command.getCurrency());
        field(payload, "description", command.getDescription());

        var sender = command.getSender();
        field(payload, "sender.name", sender == null ? null : sender.getName());
        field(payload, "sender.taxId", sender == null ? null : sender.getTaxId());
        field(payload, "sender.pixKey", sender == null ? null : sender.getPixKey());
        account(payload, "sender.account", sender == null ? null : sender.getAccount());

        var receiver = command.getReceiver();
        field(payload, "receiver.name", receiver == null ? null : receiver.getName());
        field(payload, "receiver.taxId", receiver == null ? null : receiver.getTaxId());
        field(payload, "receiver.pixKey", receiver == null ? null : receiver.getPixKey());
        account(payload, "receiver.account", receiver == null ? null : receiver.getAccount());
        return payload.toString();
    }

    private static void account(StringBuilder payload, String prefix, BankAccount account) {
        field(payload, prefix + ".number", account == null ? null : account.getNumber());
        field(payload, prefix + ".branch", account == null ? null : account.getBranch());
        field(payload, prefix + ".type", account == null || account.getType() == null ? null : account.getType().name());
        field(payload, prefix + ".bankCode", account == null ? null : account.getBankCode());
    }

    private static void field(StringBuilder payload, String name, String value) {
        String normalized = value == null ? "<null>" : value.trim();
        payload.append(name)
                .append('=')
                .append(normalized.length())
                .append(':')
                .append(normalized)
                .append('\n');
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}
