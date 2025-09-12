package br.kauan.paymentserviceprovider.adapter.output.pacs;

import br.kauan.spi.adapter.input.dtos.pacs.pacs002.ExternalPaymentTransactionStatusCode;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.ExternalStatusReasonCode;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.CashAccountTypeChoice;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.ExternalCashAccountTypeCode;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import org.springframework.stereotype.Service;

@Service
public class CodeMapping {

    public PaymentStatus mapExternalStatusCodeToPaymentStatus(ExternalPaymentTransactionStatusCode statusCode) {
        return switch (statusCode) {
            case ACCC -> PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER;
            case ACSC -> PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER;
            case ACSP -> PaymentStatus.ACCEPTED_IN_PROCESS;
            case RJCT -> PaymentStatus.REJECTED;
        };
    }

    public ExternalPaymentTransactionStatusCode mapPaymentStatusToExternalStatusCode(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> ExternalPaymentTransactionStatusCode.ACCC;
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> ExternalPaymentTransactionStatusCode.ACSC;
            case ACCEPTED_IN_PROCESS -> ExternalPaymentTransactionStatusCode.ACSP;
            case REJECTED -> ExternalPaymentTransactionStatusCode.RJCT;
            case WAITING_ACCEPTANCE, ACCEPTED_AND_SETTLED -> throw new IllegalArgumentException("No external mapping for status: " + paymentStatus);
        };
    }

    public BankAccountType mapExternalAccountTypeToBankAccountType(CashAccountTypeChoice accountTypeChoice) {
        return switch (accountTypeChoice.getAccountTypeCode()) {
            case CACC -> BankAccountType.CHECKING;
            case SVGS -> BankAccountType.SAVINGS;
            case SLRY -> BankAccountType.SALARY;
            case TRAN -> BankAccountType.PAYMENT;
        };
    }

    public ExternalCashAccountTypeCode mapBankAccountTypeToExternalCode(BankAccountType bankAccountType) {
        return switch (bankAccountType) {
            case CHECKING -> ExternalCashAccountTypeCode.CACC;
            case SAVINGS -> ExternalCashAccountTypeCode.SVGS;
            case SALARY -> ExternalCashAccountTypeCode.SLRY;
            case PAYMENT -> ExternalCashAccountTypeCode.TRAN;
        };
    }

    public ExternalStatusReasonCode mapReasonCodeToExternalStatusReasonCode(String code) {
        return ExternalStatusReasonCode.AB_03; // TODO: mapear corretamente
    }
}
