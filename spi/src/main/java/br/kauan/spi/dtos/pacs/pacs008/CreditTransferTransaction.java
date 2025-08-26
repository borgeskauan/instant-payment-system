package br.kauan.spi.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreditTransferTransaction {

    @JsonProperty(value = "PmtId", required = true)
    protected PaymentIdentification paymentIdentification;

    @JsonProperty(value = "IntrBkSttlmAmt", required = true)
    protected ActiveCurrencyAndAmount amountInformation;

    @JsonProperty(value = "Dbtr", required = true)
    protected NmIdPrivateIdentification debtorInfo;

    @JsonProperty(value = "DbtrAcct", required = true)
    protected CashAccountDebtorAccount debtorAccount;

    @JsonProperty(value = "DbtrAgt", required = true)
    protected FinancialInstitutionIdentification debtorFinancialInstitution;

    @JsonProperty(value = "CdtrAgt", required = true)
    protected FinancialInstitutionIdentification creditorFinancialInstitution;

    @JsonProperty(value = "Cdtr", required = true)
    protected IdPrivateIdentification creditorInfo;

    @JsonProperty(value = "CdtrAcct", required = true)
    protected CashAccountCreditorAccount cashAccountCreditorAccount;

    @JsonProperty(value = "RmtInf")
    protected RemittanceInformation remittanceInformation;
}
