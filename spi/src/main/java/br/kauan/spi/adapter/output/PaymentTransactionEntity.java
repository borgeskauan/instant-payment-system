package br.kauan.spi.adapter.output;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class PaymentTransactionEntity {

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
}
