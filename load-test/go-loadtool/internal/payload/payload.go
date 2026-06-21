package payload

import (
	"fmt"
	"time"
)

func Pacs008(endToEndID string, payerISPB string, receiverISPB string, amountCents int64) []byte {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	amount := float64(amountCents) / 100
	return []byte(fmt.Sprintf(`{"GrpHdr":{"MsgId":"MSG-%s","CreDtTm":%q,"NbOfTxs":1},"CdtTrfTxInf":[{"PmtId":{"EndToEndId":%q},"IntrBkSttlmAmt":{"value":%.2f,"Ccy":"BRL"},"Dbtr":{"Nm":"Load Test Payer","Id":{"PrvtId":{"Othr":{"Id":"12345678900"}}}},"DbtrAcct":{"Id":{"Othr":{"Id":987654,"Issr":1234}},"Tp":{"Cd":"CACC"}},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":%q}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":%q}}},"Cdtr":{"Nm":"Load Test Receiver","Id":{"PrvtId":{"Othr":{"Id":"98765432100"}}}},"CdtrAcct":{"Id":{"Othr":{"Id":123456,"Issr":5678}},"Tp":{"Cd":"CACC"},"Prxy":{"Id":"+5511999999999"}},"RmtInf":{"Ustrd":"Load test payment"}}]}`,
		endToEndID,
		now,
		endToEndID,
		amount,
		payerISPB,
		receiverISPB,
	))
}

func Pacs002(originalEndToEndID string) []byte {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	return []byte(fmt.Sprintf(`{"GrpHdr":{"MsgId":"STATUS-%s","CreDtTm":%q,"NbOfTxs":1},"TxInfAndSts":[{"OrgnlEndToEndId":%q,"TxSts":"ACSP"}]}`,
		originalEndToEndID,
		now,
		originalEndToEndID,
	))
}
