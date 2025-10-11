package br.kauan.spi.adapter.output.payment;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;

@Data
@Table("payment_transaction_entity")
public class PaymentTransactionEntity {

    @Id
    private String paymentId;
    private BigDecimal amount;
    private String currency;
    private String description;

    private String status;

    // Sender fields
    private String senderName;
    private String senderTaxId;
    private String senderPixKey;

    // Sender bank account fields
    private Long senderAccountNumber;
    private Integer senderAccountBranch;
    private String senderAccountType;
    private String senderBankCode;

    // Receiver fields
    private String receiverName;
    private String receiverTaxId;
    private String receiverPixKey;

    // Receiver bank account fields
    private Long receiverAccountNumber;
    private Integer receiverAccountBranch;
    private String receiverAccountType;
    private String receiverBankCode;
}
