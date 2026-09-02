package https.www_bcb_gov_br.pi.pacs_002._1;

import java.util.List;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSchemaType;


public class PaymentTransaction110 {

    @XmlElement(name = "OrgnlInstrId", required = true)
    protected String orgnlInstrId;
    @XmlElement(name = "OrgnlEndToEndId", required = true)
    protected String orgnlEndToEndId;

    @XmlElement(name = "TxSts", required = true)
    @XmlSchemaType(name = "string")
    protected ExternalPaymentTransactionStatus1Code txSts;
    @XmlElement(name = "StsRsnInf")
    protected List<StatusReasonInformation12> stsRsnInf;
}
