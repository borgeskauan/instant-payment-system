DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_transaction_entity
        WHERE status IS NULL
           OR status::TEXT NOT IN ('WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', 'REJECTED')
    ) THEN
        RAISE EXCEPTION 'payment_transaction_entity contains states that cannot be mapped to the closed payment state vocabulary';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_audit_event
        WHERE (previous_status IS NOT NULL
               AND previous_status::TEXT NOT IN ('WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', 'REJECTED'))
           OR (resulting_status IS NOT NULL
               AND resulting_status::TEXT NOT IN ('WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', 'REJECTED'))
    ) THEN
        RAISE EXCEPTION 'payment_audit_event contains states that cannot be mapped to the closed payment state vocabulary';
    END IF;
END
$$;

DROP VIEW payment_audit_event_history;

DROP INDEX uq_payment_audit_admission;
DROP INDEX uq_payment_audit_terminal_outcome;

CREATE TYPE payment_state AS ENUM (
    'WAITING_ACCEPTANCE',
    'SETTLED',
    'REJECTED'
);

CREATE TYPE payment_rejection_cause AS ENUM (
    'INSUFFICIENT_FUNDS'
);

ALTER TABLE payment_transaction_entity
    DROP CONSTRAINT payment_transaction_rejection_reason_ck;

ALTER TABLE payment_transaction_entity
    RENAME COLUMN status TO state;

ALTER TABLE payment_transaction_entity
    RENAME COLUMN rejection_reason TO rejection_cause;

ALTER TABLE payment_transaction_entity
    ALTER COLUMN state TYPE payment_state
        USING CASE state::TEXT
            WHEN 'ACCEPTED_AND_SETTLED' THEN 'SETTLED'::payment_state
            ELSE state::TEXT::payment_state
        END,
    ALTER COLUMN state SET NOT NULL,
    ALTER COLUMN rejection_cause TYPE payment_rejection_cause
        USING rejection_cause::TEXT::payment_rejection_cause,
    ADD COLUMN external_reason_codes TEXT[];

ALTER TABLE payment_transaction_entity
    ADD CONSTRAINT payment_transaction_rejection_origin_ck CHECK (
        (
            state = 'WAITING_ACCEPTANCE'
            AND rejection_cause IS NULL
            AND COALESCE(cardinality(external_reason_codes), 0) = 0
        )
        OR
        (
            state = 'SETTLED'
            AND rejection_cause IS NULL
        )
        OR
        (
            state = 'REJECTED'
            AND (
                (
                    rejection_cause = 'INSUFFICIENT_FUNDS'
                    AND COALESCE(cardinality(external_reason_codes), 0) = 0
                )
                OR
                (
                    rejection_cause IS NULL
                    AND COALESCE(cardinality(external_reason_codes), 0) > 0
                )
            )
        )
    );

ALTER TABLE payment_audit_event
    DROP CONSTRAINT payment_audit_event_shape_ck;

ALTER TABLE payment_audit_event
    RENAME COLUMN previous_status TO previous_state;

ALTER TABLE payment_audit_event
    RENAME COLUMN resulting_status TO resulting_state;

ALTER TABLE payment_audit_event
    RENAME COLUMN reason TO rejection_cause;

ALTER TABLE payment_audit_event
    ALTER COLUMN previous_state TYPE payment_state
        USING CASE previous_state::TEXT
            WHEN 'ACCEPTED_AND_SETTLED' THEN 'SETTLED'::payment_state
            ELSE previous_state::TEXT::payment_state
        END,
    ALTER COLUMN resulting_state TYPE payment_state
        USING CASE resulting_state::TEXT
            WHEN 'ACCEPTED_AND_SETTLED' THEN 'SETTLED'::payment_state
            ELSE resulting_state::TEXT::payment_state
        END,
    ALTER COLUMN rejection_cause TYPE payment_rejection_cause
        USING rejection_cause::TEXT::payment_rejection_cause,
    ADD COLUMN external_reason_codes TEXT[];

ALTER TABLE payment_audit_event
    ADD CONSTRAINT payment_audit_event_shape_ck CHECK (
        (
            event_type = 'PAYMENT_RESERVED'
            AND previous_state IS NULL
            AND resulting_state = 'WAITING_ACCEPTANCE'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND sender_delta_cents = -amount_cents
            AND receiver_delta_cents IS NULL
            AND rejection_cause IS NULL
            AND COALESCE(cardinality(external_reason_codes), 0) = 0
        )
        OR
        (
            event_type = 'PAYMENT_SETTLED'
            AND previous_state = 'WAITING_ACCEPTANCE'
            AND resulting_state = 'SETTLED'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND sender_delta_cents IS NULL
            AND receiver_delta_cents = amount_cents
            AND rejection_cause IS NULL
        )
        OR
        (
            event_type = 'PAYMENT_REJECTED'
            AND resulting_state = 'REJECTED'
            AND amount_cents IS NOT NULL
            AND sender_ispb IS NOT NULL
            AND receiver_ispb IS NOT NULL
            AND receiver_delta_cents IS NULL
            AND (
                (
                    previous_state IS NULL
                    AND sender_delta_cents IS NULL
                    AND rejection_cause = 'INSUFFICIENT_FUNDS'
                    AND COALESCE(cardinality(external_reason_codes), 0) = 0
                )
                OR
                (
                    previous_state = 'WAITING_ACCEPTANCE'
                    AND sender_delta_cents = amount_cents
                    AND rejection_cause IS NULL
                    AND COALESCE(cardinality(external_reason_codes), 0) > 0
                )
            )
        )
    );

CREATE UNIQUE INDEX uq_payment_audit_admission
    ON payment_audit_event (payment_id)
    WHERE previous_state IS NULL;

CREATE UNIQUE INDEX uq_payment_audit_terminal_outcome
    ON payment_audit_event (payment_id)
    WHERE previous_state = 'WAITING_ACCEPTANCE';

CREATE VIEW payment_audit_event_history AS
SELECT
    event_id,
    payment_id,
    event_type::TEXT AS event_type,
    previous_state::TEXT AS previous_state,
    resulting_state::TEXT AS resulting_state,
    amount_cents,
    sender_ispb,
    receiver_ispb,
    sender_delta_cents,
    receiver_delta_cents,
    rejection_cause::TEXT AS rejection_cause,
    external_reason_codes,
    occurred_at
FROM payment_audit_event
UNION ALL
SELECT
    event_id,
    payment_id,
    event_type,
    previous_status::TEXT,
    resulting_status::TEXT,
    amount_cents,
    sender_ispb,
    receiver_ispb,
    sender_delta_cents,
    receiver_delta_cents,
    reason::TEXT,
    NULL::TEXT[],
    occurred_at
FROM payment_audit_event_legacy_v16;

COMMENT ON VIEW payment_audit_event_history IS
    'Read-only union of current business facts and untouched pre-V17 history';
