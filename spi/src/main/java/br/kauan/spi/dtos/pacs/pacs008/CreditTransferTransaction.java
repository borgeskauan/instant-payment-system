package br.kauan.spi.dtos.pacs.pacs008;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreditTransferTransaction {

    @JsonPropertyCustom(value = "PmtId", required = true)
    protected PaymentIdentification paymentIdentification;

    @JsonPropertyCustom(value = "IntrBkSttlmAmt", required = true)
    protected ActiveCurrencyAndAmount amountInformation;

    @JsonPropertyCustom(value = "Dbtr", required = true)
    protected NmIdPrivateIdentification debtorInformation;

    @JsonPropertyCustom(value = "DbtrAcct", required = true)
    protected CashAccountAccount debtorAccount;

    @JsonPropertyCustom(value = "DbtrAgt", required = true)
    protected FinancialInstitutionIdentification debtorFinancialInstitution;

    @JsonPropertyCustom(value = "CdtrAgt", required = true)
    protected FinancialInstitutionIdentification creditorFinancialInstitution;

    @JsonPropertyCustom(value = "Cdtr", required = true)
    protected NmIdPrivateIdentification creditorInformation;

    @JsonPropertyCustom(value = "CdtrAcct", required = true)
    protected CashAccountAccount creditorAccount;

    @JsonPropertyCustom(value = "RmtInf")
    protected RemittanceInformation remittanceInformation;
}
