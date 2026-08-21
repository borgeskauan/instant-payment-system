DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM notification_delivery LIMIT 1) THEN
        RAISE EXCEPTION
            'notification_delivery must be empty before migrating from push delivery to pull delivery';
    END IF;
END
$$;

DROP INDEX IF EXISTS notification_delivery_claim_due_idx;
DROP INDEX IF EXISTS notification_delivery_claim_recipient_due_idx;

ALTER TABLE notification_delivery
    DROP COLUMN delivery_status,
    DROP COLUMN attempt_count,
    DROP COLUMN next_attempt_at,
    DROP COLUMN last_attempt_at,
    DROP COLUMN last_error,
    DROP COLUMN acknowledged_at,
    DROP COLUMN updated_at;

CREATE SEQUENCE notification_delivery_position_seq AS BIGINT;

ALTER TABLE notification_delivery
    ADD COLUMN delivery_position BIGINT NOT NULL
        DEFAULT nextval('notification_delivery_position_seq');

ALTER SEQUENCE notification_delivery_position_seq
    OWNED BY notification_delivery.delivery_position;

CREATE UNIQUE INDEX notification_delivery_recipient_position_idx
    ON notification_delivery (recipient_ispb, delivery_position);
