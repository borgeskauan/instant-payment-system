package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Data
@jakarta.persistence.Entity
@Table(name = "payment_transaction_entity")
public class Entity {

    @Id
    private String paymentId;
    private Long amountCents;
    private String currency;
    private String description;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "payment_status")
    private PaymentStatus status;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "payment_rejection_reason")
    private PaymentRejectionReason rejectionReason;

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
