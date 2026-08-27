ALTER TABLE payment_audit_event
    DROP CONSTRAINT payment_audit_event_shape_ck,
    DROP CONSTRAINT payment_audit_event_reason_ck;

DROP INDEX uq_payment_audit_created;
DROP INDEX uq_payment_audit_settlement;

ALTER TABLE payment_audit_event
    ALTER COLUMN event_type TYPE TEXT
        USING event_type::TEXT;

ALTER TABLE payment_audit_event
    RENAME TO payment_audit_event_legacy_v16;

ALTER SEQUENCE payment_audit_event_event_id_seq
    RENAME TO payment_audit_event_legacy_v16_event_id_seq;

COMMENT ON TABLE payment_audit_event_legacy_v16 IS
    'Immutable audit history written before the consolidated business-fact contract in V17';

DROP TYPE payment_audit_event_type;

CREATE TYPE payment_audit_event_type AS ENUM (
    'PAYMENT_RESERVED',
    'PAYMENT_SETTLED',
    'PAYMENT_REJECTED'
);

CREATE TABLE payment_audit_event (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    payment_id TEXT NOT NULL,
    event_type payment_audit_event_type NOT NULL,
    previous_status payment_status,
    resulting_status payment_status,
    amount_cents BIGINT,
    sender_ispb TEXT,
    receiver_ispb TEXT,
    sender_delta_cents BIGINT,
    receiver_delta_cents BIGINT,
    reason payment_rejection_reason,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT payment_audit_event_shape_ck CHECK (
        (
            event_type = 'PAYMENT_RESERVED'
            AND previous_status IS NULL
            AND resulting_status = 'WAITING_ACCEPTANCE'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND sender_delta_cents = -amount_cents
            AND receiver_delta_cents IS NULL
            AND reason IS NULL
        )
        OR
        (
            event_type = 'PAYMENT_SETTLED'
            AND previous_status = 'WAITING_ACCEPTANCE'
            AND resulting_status = 'ACCEPTED_AND_SETTLED'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND sender_delta_cents IS NULL
            AND receiver_delta_cents = amount_cents
            AND reason IS NULL
        )
        OR
        (
            event_type = 'PAYMENT_REJECTED'
            AND resulting_status = 'REJECTED'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND receiver_delta_cents IS NULL
            AND (
                (
                    previous_status IS NULL
                    AND sender_delta_cents IS NULL
                    AND reason = 'INSUFFICIENT_FUNDS'
                )
                OR
                (
                    previous_status = 'WAITING_ACCEPTANCE'
                    AND sender_delta_cents = amount_cents
                )
            )
        )
    )
);

SELECT setval(
    pg_get_serial_sequence('payment_audit_event', 'event_id'),
    COALESCE((SELECT MAX(event_id) FROM payment_audit_event_legacy_v16), 0) + 1,
    FALSE
);

CREATE UNIQUE INDEX uq_payment_audit_admission
    ON payment_audit_event (payment_id)
    WHERE previous_status IS NULL;

CREATE UNIQUE INDEX uq_payment_audit_terminal_outcome
    ON payment_audit_event (payment_id)
    WHERE previous_status = 'WAITING_ACCEPTANCE';

CREATE VIEW payment_audit_event_history AS
SELECT
    event_id,
    payment_id,
    event_type::TEXT AS event_type,
    previous_status,
    resulting_status,
    amount_cents,
    sender_ispb,
    receiver_ispb,
    sender_delta_cents,
    receiver_delta_cents,
    reason,
    occurred_at
FROM payment_audit_event
UNION ALL
SELECT
    event_id,
    payment_id,
    event_type,
    previous_status,
    resulting_status,
    amount_cents,
    sender_ispb,
    receiver_ispb,
    sender_delta_cents,
    receiver_delta_cents,
    reason,
    occurred_at
FROM payment_audit_event_legacy_v16;

COMMENT ON VIEW payment_audit_event_history IS
    'Read-only union of the current consolidated audit facts and the untouched pre-V17 history';
