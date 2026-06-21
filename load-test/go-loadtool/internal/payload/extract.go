package payload

import (
	"encoding/json"
	"errors"
)

const (
	KindPacs008 = "pacs008"
	KindPacs002 = "pacs002"
)

type notificationEnvelope struct {
	CdtTrfTxInf []struct {
		PmtId struct {
			EndToEndId string
			EndToEndID string
		}
	}
	TxInfAndSts []struct {
		OrgnlEndToEndId string
		OrgnlEndToEndID string
	}
}

func ExtractNotification(body []byte) (endToEndID string, kind string, err error) {
	var env notificationEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return "", "", err
	}

	if len(env.CdtTrfTxInf) > 0 {
		id := env.CdtTrfTxInf[0].PmtId.EndToEndId
		if id == "" {
			id = env.CdtTrfTxInf[0].PmtId.EndToEndID
		}
		if id != "" {
			return id, KindPacs008, nil
		}
	}

	if len(env.TxInfAndSts) > 0 {
		id := env.TxInfAndSts[0].OrgnlEndToEndId
		if id == "" {
			id = env.TxInfAndSts[0].OrgnlEndToEndID
		}
		if id != "" {
			return id, KindPacs002, nil
		}
	}

	return "", "", errors.New("notification payload does not contain a known transaction id")
}
