package br.kauan.paymentserviceprovider.adapter.output.pacs;

import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.CommonsMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.commons.GroupHeader;
import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.*;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
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

    private CashAccount createAccount(Party party) {
        var bankAccount = party.getAccount();

        return CashAccount.builder()
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
                .ispb(bankAccount.getId().getBankCode())
                .build();

        var financialIdInternal = FinancialInstitutionIdentificationInternal.builder()
                .clearingSystemMemberIdentification(clearingMemberId)
                .build();

        return FinancialInstitutionIdentification.builder()
                .financialInstitutionIdentification(financialIdInternal)
                .build();
    }

    private AccountIdentificationChoice createAccountIdentificationChoice(BankAccount bankAccount) {
        var bankAccountId = bankAccount.getId();

        var genericAccountId = GenericAccountIdentification.builder()
                .id(BigInteger.valueOf(Long.parseLong(bankAccountId.getAccountNumber())))
                .branchCode(BigInteger.valueOf(Long.parseLong(bankAccountId.getAgencyNumber())))
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
        var sender = mapToParty(transaction.getDebtorInformation(), transaction.getDebtorAccount(), transaction.getDebtorFinancialInstitution());
        var receiver = mapToParty(transaction.getCreditorInformation(), transaction.getCreditorAccount(), transaction.getCreditorFinancialInstitution());

        return PaymentTransaction.builder()
                .paymentId(extractEndToEndId(transaction.getPaymentIdentification()))
                .amount(extractAmount(transaction.getAmountInformation()))
                .currency(transaction.getAmountInformation().getCurrencyCode().name())
                .description(extractDescription(transaction.getRemittanceInformation()))
                .sender(sender)
                .receiver(receiver)
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

    private Party mapToParty(NmIdPrivateIdentification partyInfo, CashAccount account, FinancialInstitutionIdentification financialInstitutionIdentification) {
        var pixKey = account.getProxyAccountIdentification() != null ? account.getProxyAccountIdentification().getPixKey() : null;

        return Party.builder()
                .name(partyInfo.getName())
                .taxId(extractTaxId(partyInfo.getId()))
                .account(mapToBankAccount(account, financialInstitutionIdentification))
                .pixKey(pixKey)
                .build();
    }

    private String extractTaxId(PrivateIdentification privateIdentification) {
        return privateIdentification.getPersonIdentification().getOther().getCpfCnpj();
    }

    private BankAccount mapToBankAccount(CashAccount account, FinancialInstitutionIdentification financialInstitutionIdentification) {
        var mappedAccountType = codeMapping.mapExternalAccountTypeToBankAccountType(account.getAccountType());
        var bankCode = financialInstitutionIdentification.getFinancialInstitutionIdentification()
                .getClearingSystemMemberIdentification().getIspb();

        var bankAccountId = BankAccountId.builder()
                .bankCode(bankCode)
                .accountNumber(extractAccountNumber(account.getId()))
                .agencyNumber(extractBranchCode(account.getId()))
                .build();

        return BankAccount.builder()
                .id(bankAccountId)
                .type(mappedAccountType)
                .build();
    }

    private String extractAccountNumber(AccountIdentificationChoice accountId) {
        return String.valueOf(accountId.getOther().getId().intValue());
    }

    private String extractBranchCode(AccountIdentificationChoice accountId) {
        return String.valueOf(accountId.getOther().getBranchCode().intValue());
    }

    private Instant convertXmlGregorianCalendarToInstant(XMLGregorianCalendar xmlGregorianCalendar) {
        return xmlGregorianCalendar.toGregorianCalendar().toZonedDateTime().toInstant();
    }
}