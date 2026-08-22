ALTER TABLE outbound_notification
    DROP COLUMN event_type,
    DROP COLUMN payment_id,
    DROP COLUMN notification_status,
    DROP COLUMN schema_version;
