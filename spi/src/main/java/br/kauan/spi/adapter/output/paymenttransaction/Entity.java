package br.kauan.spi.adapter.output.paymenttransaction;

import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@jakarta.persistence.Entity
@Table(name = "payment_transaction_entity")
public class Entity {

    @Id
    private String paymentId;
    private Long amountCents;
    private String currency;
    private String description;

    private String status;

    // Sender fields
    private String senderName;
    private String senderTaxId;
    private String senderPixKey;

    // Sender bank account fields
    private String senderAccountNumber;
    private String senderAccountBranch;
    private String senderAccountType;
    private String senderBankCode;

    // Receiver fields
    private String receiverName;
    private String receiverTaxId;
    private String receiverPixKey;

    // Receiver bank account fields
    private String receiverAccountNumber;
    private String receiverAccountBranch;
    private String receiverAccountType;
    private String receiverBankCode;

    private String requestFingerprint;
    private String requestFingerprintVersion;
}
