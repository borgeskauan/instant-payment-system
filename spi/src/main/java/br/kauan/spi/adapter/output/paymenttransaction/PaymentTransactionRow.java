package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentState;
import lombok.Data;

@Data
class PaymentTransactionRow {

    private String paymentId;
    private Long amountCents;
    private String currency;
    private String description;

    private PaymentState state;

    private PaymentRejectionCause rejectionCause;

    private String[] externalReasonCodes;

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

    private byte[] requestFingerprint;
    private Short requestFingerprintVersion;
}
