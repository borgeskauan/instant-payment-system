ALTER TABLE outbound_notification
    RENAME TO notification_outbox;

ALTER TABLE notification_outbox
    RENAME CONSTRAINT outbound_notification_pkey TO notification_outbox_pkey;
