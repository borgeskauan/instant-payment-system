package payload

import (
	"strconv"
	"time"
)

func Pacs008(endToEndID string, payerISPB string, receiverISPB string, amountCents int64) []byte {
	payload := make([]byte, 0, 768)
	payload = append(payload, `{"GrpHdr":{"MsgId":"MSG-`...)
	payload = append(payload, endToEndID...)
	payload = append(payload, `","CreDtTm":"`...)
	payload = time.Now().UTC().AppendFormat(payload, time.RFC3339Nano)
	payload = append(payload, `","NbOfTxs":1},"CdtTrfTxInf":[{"PmtId":{"EndToEndId":"`...)
	payload = append(payload, endToEndID...)
	payload = append(payload, `"},"IntrBkSttlmAmt":{"value":`...)
	payload = strconv.AppendFloat(payload, float64(amountCents)/100, 'f', 2, 64)
	payload = append(payload, `,"Ccy":"BRL"},"Dbtr":{"Nm":"Load Test Payer","Id":{"PrvtId":{"Othr":{"Id":"12345678900"}}}},"DbtrAcct":{"Id":{"Othr":{"Id":987654,"Issr":1234}},"Tp":{"Cd":"CACC"}},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"`...)
	payload = append(payload, payerISPB...)
	payload = append(payload, `"}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"`...)
	payload = append(payload, receiverISPB...)
	payload = append(payload, `"}}},"Cdtr":{"Nm":"Load Test Receiver","Id":{"PrvtId":{"Othr":{"Id":"98765432100"}}}},"CdtrAcct":{"Id":{"Othr":{"Id":123456,"Issr":5678}},"Tp":{"Cd":"CACC"},"Prxy":{"Id":"+5511999999999"}},"RmtInf":{"Ustrd":"Load test payment"}}]}`...)
	return payload
}

func Pacs002(originalEndToEndID string) []byte {
	payload := make([]byte, 0, 256)
	payload = append(payload, `{"GrpHdr":{"MsgId":"STATUS-`...)
	payload = append(payload, originalEndToEndID...)
	payload = append(payload, `","CreDtTm":"`...)
	payload = time.Now().UTC().AppendFormat(payload, time.RFC3339Nano)
	payload = append(payload, `","NbOfTxs":1},"TxInfAndSts":[{"OrgnlEndToEndId":"`...)
	payload = append(payload, originalEndToEndID...)
	payload = append(payload, `","TxSts":"ACSP"}]}`...)
	return payload
}
