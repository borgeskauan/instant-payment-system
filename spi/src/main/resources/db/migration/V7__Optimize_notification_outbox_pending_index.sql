DROP INDEX notification_outbox_pending_idx;

CREATE INDEX notification_outbox_pending_idx
    ON notification_outbox (next_attempt_at, communication_id)
    WHERE publication_status = 'PENDING';
