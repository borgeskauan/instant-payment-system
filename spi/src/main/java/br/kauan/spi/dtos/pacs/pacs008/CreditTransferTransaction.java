package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Data;

@Data
public class CreditTransferTransaction {

    @JsonPropertyCustom(value = "PmtId", required = true)
    protected PaymentIdentification paymentIdentification;

    @JsonPropertyCustom(value = "IntrBkSttlmAmt", required = true)
    protected ActiveCurrencyAndAmount amountInformation;

    @JsonPropertyCustom(value = "Dbtr", required = true)
    protected NmIdPrivateIdentification debtorInfo;

    @JsonPropertyCustom(value = "DbtrAcct", required = true)
    protected CashAccountDebtorAccount debtorAccount;

    @JsonPropertyCustom(value = "DbtrAgt", required = true)
    protected FinancialInstitutionIdentification debtorFinancialInstitution;

    @JsonPropertyCustom(value = "CdtrAgt", required = true)
    protected FinancialInstitutionIdentification creditorFinancialInstitution;

    @JsonPropertyCustom(value = "Cdtr", required = true)
    protected NmIdPrivateIdentification creditorInfo;

    @JsonPropertyCustom(value = "CdtrAcct", required = true)
    protected CashAccountCreditorAccount creditorAccount;

    @JsonPropertyCustom(value = "RmtInf")
    protected RemittanceInformation remittanceInformation;
}
