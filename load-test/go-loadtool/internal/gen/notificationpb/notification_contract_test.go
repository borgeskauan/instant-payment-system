package notificationpb

import (
	"reflect"
	"testing"
)

func TestNotificationPayloadIsBytes(t *testing.T) {
	payloadType := reflect.TypeOf(Notification{}.Payload)

	if payloadType.Kind() != reflect.Slice ||
		payloadType.Elem().Kind() != reflect.Uint8 {
		t.Fatalf("Notification.Payload type = %s, want []byte", payloadType)
	}
}

func TestNotificationDoesNotCarryIspb(t *testing.T) {
	if _, ok := reflect.TypeOf(Notification{}).FieldByName("Ispb"); ok {
		t.Fatal("Notification carries Ispb, but the stream subscription already identifies the ISPB")
	}
}

func TestNotificationDoesNotCarryPushAcknowledgementIdentity(t *testing.T) {
	if _, ok := reflect.TypeOf(Notification{}).FieldByName("DeliveryId"); ok {
		t.Fatal("Notification carries obsolete push delivery identity")
	}
}

func TestPullRequestCarriesOnlyCursor(t *testing.T) {
	typeOfRequest := reflect.TypeOf(PullRequest{})
	if _, ok := typeOfRequest.FieldByName("Cursor"); !ok {
		t.Fatal("PullRequest does not carry cursor")
	}
	if _, ok := typeOfRequest.FieldByName("MaxBatch"); ok {
		t.Fatal("PullRequest exposes batch size even though the protocol fixes the limit")
	}
}
