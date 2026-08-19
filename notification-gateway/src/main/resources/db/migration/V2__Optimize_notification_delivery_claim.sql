DROP INDEX IF EXISTS notification_delivery_pending_idx;
DROP INDEX IF EXISTS notification_delivery_lease_idx;

ALTER TABLE notification_delivery
    DROP COLUMN lease_until;

CREATE INDEX notification_delivery_claim_due_idx
    ON notification_delivery (next_attempt_at)
    WHERE delivery_status IN (
        'PENDING',
        'RETRYABLE_FAILED',
        'IN_FLIGHT'
    );

CREATE INDEX notification_delivery_claim_recipient_due_idx
    ON notification_delivery (recipient_ispb, next_attempt_at)
    WHERE delivery_status IN (
        'PENDING',
        'RETRYABLE_FAILED',
        'IN_FLIGHT'
    );
