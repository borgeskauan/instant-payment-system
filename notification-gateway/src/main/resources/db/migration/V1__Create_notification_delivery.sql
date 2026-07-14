CREATE TABLE notification_delivery (
    communication_id TEXT PRIMARY KEY,
    recipient_ispb TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payment_id TEXT NOT NULL,
    notification_status TEXT,
    schema_version TEXT NOT NULL,
    payload BYTEA NOT NULL,
    delivery_status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_until TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    acknowledged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX notification_delivery_pending_idx
    ON notification_delivery (recipient_ispb, next_attempt_at, delivery_status);

CREATE INDEX notification_delivery_lease_idx
    ON notification_delivery (lease_until)
    WHERE delivery_status = 'IN_FLIGHT';
