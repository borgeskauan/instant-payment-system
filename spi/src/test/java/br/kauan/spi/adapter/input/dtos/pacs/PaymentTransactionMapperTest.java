package br.kauan.spi.adapter.input.dtos.pacs;

import br.kauan.spi.adapter.input.dtos.pacs.commons.CommonsMapper;
import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.*;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import org.junit.jupiter.api.Test;

import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentTransactionMapperTest {

    @Test
    void fromRegulatoryRequestParsesCreationTimestampWithoutGregorianCalendarAllocation() {
        XMLGregorianCalendar timestamp = mock(XMLGregorianCalendar.class);
        stubTimestamp(timestamp);
        when(timestamp.toGregorianCalendar())
                .thenThrow(new AssertionError("toGregorianCalendar should not be used on the hot path"));
        PaymentTransactionMapper mapper = new PaymentTransactionMapper(mock(CommonsMapper.class), new CodeMapping());

        var batch = mapper.fromRegulatoryRequest(FIToFICustomerCreditTransfer.builder()
                .groupHeader(GroupHeader.builder()
                        .messageId("batch-1")
                        .creationTimestamp(timestamp)
                        .numberOfTransactions(BigInteger.ONE)
                        .build())
                .creditTransferTransactions(List.of(creditTransferTransaction()))
                .build());

        assertThat(batch.getBatchDetails().getCreatedAt())
                .isEqualTo(Instant.parse("2026-06-23T20:00:01.123Z"));
        assertThat(batch.getTransactions()).hasSize(1);
        assertThat(batch.getTransactions().getFirst().getAmountCents()).isEqualTo(1000L);
        assertThat(batch.getTransactions().getFirst().getSender().getAccount().getType())
                .isEqualTo(BankAccountType.CHECKING);
    }

    private static CreditTransferTransaction creditTransferTransaction() {
        return CreditTransferTransaction.builder()
                .paymentIdentification(PaymentIdentification.builder()
                        .endToEndId("E2E-1")
                        .build())
                .amountInformation(ActiveCurrencyAndAmount.builder()
                        .value(BigDecimal.TEN)
                        .currencyCode(ActiveCurrencyCode.BRL)
                        .build())
                .debtorInformation(party("Sender", "11111111111"))
                .debtorAccount(account(123L, 1, ExternalCashAccountTypeCode.CACC, "sender-pix"))
                .debtorFinancialInstitution(financialInstitution("10000001"))
                .creditorInformation(party("Receiver", "22222222222"))
                .creditorAccount(account(456L, 2, ExternalCashAccountTypeCode.SVGS, "receiver-pix"))
                .creditorFinancialInstitution(financialInstitution("20000002"))
                .remittanceInformation(RemittanceInformation.builder()
                        .additionalInformation("test payment")
                        .build())
                .build();
    }

    private static NmIdPrivateIdentification party(String name, String taxId) {
        return NmIdPrivateIdentification.builder()
                .name(name)
                .id(PrivateIdentification.builder()
                        .personIdentification(PersonIdentification.builder()
                                .other(GenericPersonIdentification.builder()
                                        .cpfCnpj(taxId)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static CashAccount account(long number, int branch, ExternalCashAccountTypeCode type, String pixKey) {
        return CashAccount.builder()
                .id(AccountIdentificationChoice.builder()
                        .other(GenericAccountIdentification.builder()
                                .id(BigInteger.valueOf(number))
                                .branchCode(BigInteger.valueOf(branch))
                                .build())
                        .build())
                .accountType(CashAccountTypeChoice.builder()
                        .accountTypeCode(type)
                        .build())
                .proxyAccountIdentification(ProxyAccountIdentification.builder()
                        .pixKey(pixKey)
                        .build())
                .build();
    }

    private static FinancialInstitutionIdentification financialInstitution(String ispb) {
        return FinancialInstitutionIdentification.builder()
                .financialInstitutionIdentification(FinancialInstitutionIdentificationInternal.builder()
                        .clearingSystemMemberIdentification(ClearingSystemMemberIdentification.builder()
                                .ispb(ispb)
                                .build())
                        .build())
                .build();
    }

    private static void stubTimestamp(XMLGregorianCalendar timestamp) {
        when(timestamp.getYear()).thenReturn(2026);
        when(timestamp.getMonth()).thenReturn(6);
        when(timestamp.getDay()).thenReturn(23);
        when(timestamp.getHour()).thenReturn(20);
        when(timestamp.getMinute()).thenReturn(0);
        when(timestamp.getSecond()).thenReturn(1);
        when(timestamp.getFractionalSecond()).thenReturn(new BigDecimal("0.123"));
        when(timestamp.getTimezone()).thenReturn(0);
    }
}
