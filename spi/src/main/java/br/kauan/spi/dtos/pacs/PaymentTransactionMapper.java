package br.kauan.spi.dtos.pacs;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.transfer.*;
import br.kauan.spi.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.dtos.pacs.pacs008.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentTransactionMapper {

    private final CommonsMapper commonsMapper;
    private final CodeMapping codeMapping;

    public FIToFICustomerCreditTransfer toRegulatoryRequest(PaymentBatch internalBatch) {
        var groupHeader = commonsMapper.createGroupHeader(internalBatch.getBatchDetails());
        var creditTransferTransactions = mapPaymentTransactionsToCreditTransferTransactions(internalBatch.getTransactions());

        return FIToFICustomerCreditTransfer.builder()
                .groupHeader(groupHeader)
                .creditTransferTransactions(creditTransferTransactions)
                .build();
    }

    public PaymentBatch fromRegulatoryRequest(FIToFICustomerCreditTransfer regulatoryRequest) {
        var batchDetails = mapGroupHeaderToBatchDetails(regulatoryRequest.getGroupHeader());
        var transactions = mapCreditTransferTransactionsToPaymentTransactions(regulatoryRequest.getCreditTransferTransactions());

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(transactions)
                .build();
    }

    private BatchDetails mapGroupHeaderToBatchDetails(GroupHeader groupHeader) {
        return BatchDetails.builder()
                .id(groupHeader.getMessageId())
                .createdAt(convertXmlGregorianCalendarToInstant(groupHeader.getCreationTimestamp()))
                .totalTransactions(groupHeader.getNumberOfTransactions().intValue())
                .build();
    }

    private List<CreditTransferTransaction> mapPaymentTransactionsToCreditTransferTransactions(List<PaymentTransaction> paymentTransactions) {
        return paymentTransactions.stream()
                .map(this::mapPaymentTransactionToCreditTransferTransaction)
                .toList();
    }

    private CreditTransferTransaction mapPaymentTransactionToCreditTransferTransaction(PaymentTransaction paymentTransaction) {
        return CreditTransferTransaction.builder()
                .paymentIdentification(createPaymentIdentification(paymentTransaction))
                .amountInformation(createAmountInformation(paymentTransaction))
                .debtorInformation(createPartyInformation(paymentTransaction.getSender()))
                .debtorAccount(createAccount(paymentTransaction.getSender()))
                .debtorFinancialInstitution(createFinancialInstitutionId(paymentTransaction.getSender().getAccount()))
                .creditorInformation(createPartyInformation(paymentTransaction.getReceiver()))
                .creditorAccount(createAccount(paymentTransaction.getReceiver()))
                .creditorFinancialInstitution(createFinancialInstitutionId(paymentTransaction.getReceiver().getAccount()))
                .remittanceInformation(createRemittanceInformation(paymentTransaction))
                .build();
    }

    private RemittanceInformation createRemittanceInformation(PaymentTransaction paymentTransaction) {
        return RemittanceInformation.builder()
                .additionalInformation(paymentTransaction.getDescription())
                .build();
    }

    private CashAccountAccount createAccount(Party party) {
        var bankAccount = party.getAccount();

        return CashAccountAccount.builder()
                .id(createAccountIdentificationChoice(bankAccount))
                .accountType(createCashAccountTypeChoice(bankAccount))
                .proxyAccountIdentification(createProxyAccountIdentification(party))
                .build();
    }

    private ProxyAccountIdentification createProxyAccountIdentification(Party party) {
        return ProxyAccountIdentification.builder()
                .pixKey(party.getPixKey())
                .build();
    }

    private FinancialInstitutionIdentification createFinancialInstitutionId(BankAccount bankAccount) {
        var clearingMemberId = ClearingSystemMemberIdentification.builder()
                .ispb(bankAccount.getBankCode())
                .build();

        var financialIdInternal = FinancialInstitutionIdentificationInternal.builder()
                .clearingSystemMemberIdentification(clearingMemberId)
                .build();

        return FinancialInstitutionIdentification.builder()
                .financialInstitutionIdentification(financialIdInternal)
                .build();
    }

    private AccountIdentificationChoice createAccountIdentificationChoice(BankAccount bankAccount) {
        var genericAccountId = GenericAccountIdentification.builder()
                .id(BigInteger.valueOf(bankAccount.getNumber()))
                .branchCode(BigInteger.valueOf(bankAccount.getBranch()))
                .build();

        return AccountIdentificationChoice.builder()
                .other(genericAccountId)
                .build();
    }

    private CashAccountTypeChoice createCashAccountTypeChoice(BankAccount bankAccount) {
        var mappedAccountType = codeMapping.mapBankAccountTypeToExternalCode(bankAccount.getType());

        return CashAccountTypeChoice.builder()
                .accountTypeCode(mappedAccountType)
                .build();
    }

    private NmIdPrivateIdentification createPartyInformation(Party party) {
        return NmIdPrivateIdentification.builder()
                .name(party.getName())
                .id(createPrivateIdentification(party))
                .build();
    }

    private PrivateIdentification createPrivateIdentification(Party party) {
        return PrivateIdentification.builder()
                .personIdentification(createPersonIdentification(party))
                .build();
    }

    private PersonIdentification createPersonIdentification(Party party) {
        return PersonIdentification.builder()
                .other(createGenericPersonIdentification(party))
                .build();
    }

    private GenericPersonIdentification createGenericPersonIdentification(Party party) {
        return GenericPersonIdentification.builder()
                .cpfCnpj(party.getTaxId())
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

    private List<PaymentTransaction> mapCreditTransferTransactionsToPaymentTransactions(List<CreditTransferTransaction> creditTransferTransactions) {
        return creditTransferTransactions.stream()
                .map(this::mapCreditTransferTransactionToPaymentTransaction)
                .toList();
    }

    private PaymentTransaction mapCreditTransferTransactionToPaymentTransaction(CreditTransferTransaction transaction) {
        return PaymentTransaction.builder()
                .paymentId(extractEndToEndId(transaction.getPaymentIdentification()))
                .amount(extractAmount(transaction.getAmountInformation()))
                .description(extractDescription(transaction.getRemittanceInformation()))
                .sender(mapToParty(transaction.getDebtorInformation(), transaction.getDebtorAccount()))
                .receiver(mapToParty(transaction.getCreditorInformation(), transaction.getCreditorAccount()))
                .build();
    }

    private String extractEndToEndId(PaymentIdentification paymentIdentification) {
        return paymentIdentification.getEndToEndId();
    }

    private BigDecimal extractAmount(ActiveCurrencyAndAmount amountInformation) {
        return amountInformation.getValue();
    }

    private String extractDescription(RemittanceInformation remittanceInformation) {
        return remittanceInformation.getAdditionalInformation();
    }

    private Party mapToParty(NmIdPrivateIdentification partyInfo, CashAccountAccount account) {
        return Party.builder()
                .name(partyInfo.getName())
                .taxId(extractTaxId(partyInfo.getId()))
                .account(mapToBankAccount(account))
                .build();
    }

    private String extractTaxId(PrivateIdentification privateIdentification) {
        return privateIdentification.getPersonIdentification().getOther().getCpfCnpj();
    }

    private BankAccount mapToBankAccount(CashAccountAccount account) {
        var mappedAccountType = codeMapping.mapExternalAccountTypeToBankAccountType(account.getAccountType());

        return BankAccount.builder()
                .number(extractAccountNumber(account.getId()))
                .branch(extractBranchCode(account.getId()))
                .type(mappedAccountType)
                .build();
    }

    private long extractAccountNumber(AccountIdentificationChoice accountId) {
        return accountId.getOther().getId().longValue();
    }

    private int extractBranchCode(AccountIdentificationChoice accountId) {
        return accountId.getOther().getBranchCode().intValue();
    }

    private Instant convertXmlGregorianCalendarToInstant(XMLGregorianCalendar xmlGregorianCalendar) {
        return xmlGregorianCalendar.toGregorianCalendar().toZonedDateTime().toInstant();
    }
}