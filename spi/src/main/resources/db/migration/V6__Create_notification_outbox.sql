CREATE TABLE notification_outbox (
    communication_id TEXT PRIMARY KEY,
    recipient_ispb TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payment_id TEXT NOT NULL,
    notification_status TEXT,
    schema_version TEXT NOT NULL,
    payload BYTEA NOT NULL,
    publication_status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT notification_outbox_publication_status_check
        CHECK (publication_status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT notification_outbox_published_at_check
        CHECK (publication_status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX notification_outbox_pending_idx
    ON notification_outbox (publication_status, next_attempt_at);
