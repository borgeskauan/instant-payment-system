CREATE TABLE payment_audit_event (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    previous_status TEXT,
    resulting_status TEXT,
    amount_cents BIGINT,
    sender_ispb TEXT,
    receiver_ispb TEXT,
    sender_delta_cents BIGINT,
    receiver_delta_cents BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT payment_audit_event_type_ck CHECK (
        event_type IN (
            'PAYMENT_CREATED',
            'PAYMENT_STATUS_CHANGED',
            'SETTLEMENT_APPLIED'
        )
    ),
    CONSTRAINT payment_audit_event_shape_ck CHECK (
        (
            event_type = 'PAYMENT_CREATED'
            AND previous_status IS NULL
            AND resulting_status = 'WAITING_ACCEPTANCE'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND sender_delta_cents IS NULL
            AND receiver_delta_cents IS NULL
        )
        OR
        (
            event_type = 'PAYMENT_STATUS_CHANGED'
            AND previous_status IS NOT NULL
            AND resulting_status IS NOT NULL
            AND previous_status <> resulting_status
            AND amount_cents IS NULL
            AND sender_ispb IS NULL
            AND receiver_ispb IS NULL
            AND sender_delta_cents IS NULL
            AND receiver_delta_cents IS NULL
        )
        OR
        (
            event_type = 'SETTLEMENT_APPLIED'
            AND previous_status IS NULL
            AND resulting_status IS NULL
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND sender_delta_cents = -amount_cents
            AND receiver_delta_cents = amount_cents
        )
    )
);

CREATE INDEX idx_payment_audit_event_payment
    ON payment_audit_event (payment_id, event_id);

CREATE UNIQUE INDEX uq_payment_audit_created
    ON payment_audit_event (payment_id)
    WHERE event_type = 'PAYMENT_CREATED';

CREATE UNIQUE INDEX uq_payment_audit_settlement
    ON payment_audit_event (payment_id)
    WHERE event_type = 'SETTLEMENT_APPLIED';
