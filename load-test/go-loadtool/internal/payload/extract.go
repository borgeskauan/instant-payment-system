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

type Notification struct {
	EndToEndID string
	Kind       string
}

func ExtractNotification(body []byte) (endToEndID string, kind string, err error) {
	notifications, err := ExtractNotifications(body)
	if err != nil {
		return "", "", err
	}
	return notifications[0].EndToEndID, notifications[0].Kind, nil
}

func ExtractNotifications(body []byte) ([]Notification, error) {
	var env notificationEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, err
	}

	if len(env.CdtTrfTxInf) > 0 {
		notifications := make([]Notification, 0, len(env.CdtTrfTxInf))
		for _, tx := range env.CdtTrfTxInf {
			id := tx.PmtId.EndToEndId
			if id == "" {
				id = tx.PmtId.EndToEndID
			}
			if id != "" {
				notifications = append(notifications, Notification{
					EndToEndID: id,
					Kind:       KindPacs008,
				})
			}
		}
		if len(notifications) > 0 {
			return notifications, nil
		}
	}

	if len(env.TxInfAndSts) > 0 {
		notifications := make([]Notification, 0, len(env.TxInfAndSts))
		for _, tx := range env.TxInfAndSts {
			id := tx.OrgnlEndToEndId
			if id == "" {
				id = tx.OrgnlEndToEndID
			}
			if id != "" {
				notifications = append(notifications, Notification{
					EndToEndID: id,
					Kind:       KindPacs002,
				})
			}
		}
		if len(notifications) > 0 {
			return notifications, nil
		}
	}

	return nil, errors.New("notification payload does not contain a known transaction id")
}
