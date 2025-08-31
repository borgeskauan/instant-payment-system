package br.kauan.spi.dtos.pacs;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.transfer.*;
import br.kauan.spi.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.dtos.pacs.pacs008.*;
import org.springframework.stereotype.Service;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;
import java.time.Instant;

@Service
public class PaymentTransactionMapper {

    private final CommonsMapper commonsMapper;

    public PaymentTransactionMapper(CommonsMapper commonsMapper) {
        this.commonsMapper = commonsMapper;
    }

    public FIToFICustomerCreditTransfer toRegulatoryRequest(PaymentBatch internalBatch) {
        var groupHeader = commonsMapper.createGroupHeader(internalBatch.getBatchDetails());
        var creditTransferTransactions = internalBatch.getTransactions().stream()
                .map(this::toCreditTransferTransaction)
                .toList();

        return FIToFICustomerCreditTransfer.builder()
                .groupHeader(groupHeader)
                .creditTransferTransactions(creditTransferTransactions)
                .build();
    }

    public PaymentBatch fromRegulatoryRequest(FIToFICustomerCreditTransfer regulatoryRequest) {
        var groupHeader = regulatoryRequest.getGroupHeader();
        var transactions = regulatoryRequest.getCreditTransferTransactions().stream()
                .map(this::fromCreditTransferTransaction)
                .toList();

        var batchDetails = BatchDetails.builder()
                .id(groupHeader.getMessageId())
                .createdAt(convertXmlGregorianCalendarToInstant(groupHeader.getCreationTimestamp()))
                .totalTransactions(groupHeader.getNumberOfTransactions().intValue())
                .build();

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(transactions)
                .build();
    }

    private CreditTransferTransaction toCreditTransferTransaction(PaymentTransaction paymentTransaction) {
        var paymentIdentification = createPaymentIdentification(paymentTransaction);
        var amountInformation = createAmountInformation(paymentTransaction);
        var debtorInformation = createPartyInformation(paymentTransaction.getSender());
        var debtorAccount = createAccount(paymentTransaction.getSender());
        var debtorFinancialInstitution = createFinancialInstitutionId(paymentTransaction.getSender().getAccount());
        var creditorInformation = createPartyInformation(paymentTransaction.getReceiver());
        var creditorAccount = createAccount(paymentTransaction.getReceiver());
        var creditorFinancialInstitution = createFinancialInstitutionId(paymentTransaction.getReceiver().getAccount());
        var remittanceInformation = createRemittanceInformation(paymentTransaction);

        return CreditTransferTransaction.builder()
                .paymentIdentification(paymentIdentification)
                .amountInformation(amountInformation)
                .debtorInformation(debtorInformation)
                .debtorAccount(debtorAccount)
                .debtorFinancialInstitution(debtorFinancialInstitution)
                .creditorInformation(creditorInformation)
                .creditorAccount(creditorAccount)
                .creditorFinancialInstitution(creditorFinancialInstitution)
                .remittanceInformation(remittanceInformation)
                .build();
    }

    private RemittanceInformation createRemittanceInformation(PaymentTransaction paymentTransaction) {
        return RemittanceInformation.builder()
                .additionalInformation(paymentTransaction.getDescription())
                .build();
    }

    private CashAccountAccount createAccount(Party party) {
        var bankAccount = party.getAccount();

        var accountId = createAccountIdentificationChoice(bankAccount);
        var accountType = createCashAccountTypeChoice(bankAccount);

        var proxyId = ProxyAccountIdentification.builder()
                .pixKey(party.getPixKey())
                .build();

        return CashAccountAccount.builder()
                .id(accountId)
                .accountType(accountType)
                .proxyAccountIdentification(proxyId)
                .build();
    }

    private FinancialInstitutionIdentification createFinancialInstitutionId(BankAccount paymentTransaction) {
        var clearingMemberId = ClearingSystemMemberIdentification.builder()
                .ispb(paymentTransaction.getBankCode())
                .build();

        var financialIdInternal = FinancialInstitutionIdentificationInternal.builder()
                .clearingSystemMemberIdentification(clearingMemberId)
                .build();

        return FinancialInstitutionIdentification.builder()
                .financialInstitutionIdentification(financialIdInternal)
                .build();
    }

    private static AccountIdentificationChoice createAccountIdentificationChoice(BankAccount bankAccount) {
        var genericAccountId = GenericAccountIdentification.builder()
                .id(BigInteger.valueOf(bankAccount.getNumber()))
                .branchCode(BigInteger.valueOf(bankAccount.getBranch()))
                .build();

        return AccountIdentificationChoice.builder()
                .other(genericAccountId)
                .build();
    }

    private static CashAccountTypeChoice createCashAccountTypeChoice(BankAccount bankAccount) {
        var mappedAccountType = switch (bankAccount.getType()) {
            case CHECKING -> ExternalCashAccountTypeCode.CACC;
            case SAVINGS -> ExternalCashAccountTypeCode.SVGS;
            case SALARY -> ExternalCashAccountTypeCode.SLRY;
            case PAYMENT -> ExternalCashAccountTypeCode.TRAN;
        };

        return CashAccountTypeChoice.builder()
                .accountTypeCode(mappedAccountType)
                .build();
    }

    private NmIdPrivateIdentification createPartyInformation(Party party) {
        var taxId = GenericPersonIdentification.builder()
                .cpfCnpj(party.getTaxId())
                .build();

        var wrappedTaxId = PersonIdentification.builder()
                .other(taxId)
                .build();

        var privateId = PrivateIdentification.builder()
                .personIdentification(wrappedTaxId)
                .build();

        return NmIdPrivateIdentification.builder()
                .name(party.getName())
                .id(privateId)
                .build();
    }

    private ActiveCurrencyAndAmount createAmountInformation(PaymentTransaction paymentTransaction) {
        return ActiveCurrencyAndAmount.builder()
                .value(paymentTransaction.getAmount())
                .currencyCode(ActiveCurrencyCode.BRL)
                .build();
    }

    private PaymentIdentification createPaymentIdentification(PaymentTransaction paymentTransaction) {
        return PaymentIdentification.builder()
                .endToEndId(paymentTransaction.getPaymentId())
                .build();
    }

    private PaymentTransaction fromCreditTransferTransaction(CreditTransferTransaction transaction) {
        var sender = fromPartyInformation(transaction.getDebtorInformation(), transaction.getDebtorAccount());
        var receiver = fromPartyInformation(transaction.getCreditorInformation(), transaction.getCreditorAccount());

        return PaymentTransaction.builder()
                .paymentId(transaction.getPaymentIdentification().getEndToEndId())
                .amount(transaction.getAmountInformation().getValue())
                .description(transaction.getRemittanceInformation().getAdditionalInformation())
                .sender(sender)
                .receiver(receiver)
                .build();
    }

    private Party fromPartyInformation(NmIdPrivateIdentification partyInfo, CashAccountAccount account) {
        var bankAccount = BankAccount.builder()
                .number(account.getId().getOther().getId().longValue())
                .branch(account.getId().getOther().getBranchCode().intValue())
                .build();

        return Party.builder()
                .name(partyInfo.getName())
                .taxId(partyInfo.getId().getPersonIdentification().getOther().getCpfCnpj())
                .account(bankAccount)
                .build();
    }

    private Instant convertXmlGregorianCalendarToInstant(XMLGregorianCalendar xmlGregorianCalendar) {
        return xmlGregorianCalendar.toGregorianCalendar().toZonedDateTime().toInstant();
    }
}
