ALTER TABLE notification_outbox
    RENAME TO outbound_notification;

DROP INDEX notification_outbox_pending_idx;

ALTER TABLE outbound_notification
    DROP CONSTRAINT notification_outbox_publication_status_check,
    DROP CONSTRAINT notification_outbox_published_at_check,
    DROP COLUMN publication_status,
    DROP COLUMN attempt_count,
    DROP COLUMN next_attempt_at,
    DROP COLUMN last_error,
    DROP COLUMN published_at,
    DROP COLUMN updated_at;

ALTER TABLE outbound_notification
    RENAME CONSTRAINT notification_outbox_pkey TO outbound_notification_pkey;
