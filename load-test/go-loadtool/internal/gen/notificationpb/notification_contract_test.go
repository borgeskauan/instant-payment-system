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

func TestNotificationCarriesDeliveryId(t *testing.T) {
	if _, ok := reflect.TypeOf(Notification{}).FieldByName("DeliveryId"); !ok {
		t.Fatal("Notification does not carry DeliveryId")
	}
}
