package notificationpb

import (
	"reflect"
	"testing"
)

func TestNotificationBatchPayloadsIsRepeatedBytes(t *testing.T) {
	payloadType := reflect.TypeOf(NotificationBatch{}.Payloads)

	if payloadType.Kind() != reflect.Slice ||
		payloadType.Elem().Kind() != reflect.Slice ||
		payloadType.Elem().Elem().Kind() != reflect.Uint8 {
		t.Fatalf("NotificationBatch.Payloads type = %s, want [][]byte", payloadType)
	}
}

func TestNotificationBatchDoesNotCarryIspb(t *testing.T) {
	if _, ok := reflect.TypeOf(NotificationBatch{}).FieldByName("Ispb"); ok {
		t.Fatal("NotificationBatch carries Ispb, but the stream subscription already identifies the ISPB")
	}
}
