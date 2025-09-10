package br.kauan.spi.adapter.input.dtos.pacs.pacs008;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreditTransferTransaction {

    @JsonProperty(value = "PmtId", required = true)
    protected PaymentIdentification paymentIdentification;

    @JsonProperty(value = "IntrBkSttlmAmt", required = true)
    protected ActiveCurrencyAndAmount amountInformation;

    @JsonProperty(value = "Dbtr", required = true)
    protected NmIdPrivateIdentification debtorInformation;

    @JsonProperty(value = "DbtrAcct", required = true)
    protected CashAccount debtorAccount;

    @JsonProperty(value = "DbtrAgt", required = true)
    protected FinancialInstitutionIdentification debtorFinancialInstitution;

    @JsonProperty(value = "CdtrAgt", required = true)
    protected FinancialInstitutionIdentification creditorFinancialInstitution;

    @JsonProperty(value = "Cdtr", required = true)
    protected NmIdPrivateIdentification creditorInformation;

    @JsonProperty(value = "CdtrAcct", required = true)
    protected CashAccount creditorAccount;

    @JsonProperty(value = "RmtInf")
    protected RemittanceInformation remittanceInformation;
}
