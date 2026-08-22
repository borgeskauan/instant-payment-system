CREATE TYPE payment_status AS ENUM (
    'WAITING_ACCEPTANCE',
    'ACCEPTED_AND_SETTLED',
    'ACCEPTED_AND_SETTLED_FOR_RECEIVER',
    'ACCEPTED_AND_SETTLED_FOR_SENDER',
    'ACCEPTED_IN_PROCESS',
    'REJECTED'
);

CREATE TYPE payment_rejection_reason AS ENUM (
    'INSUFFICIENT_FUNDS'
);

CREATE TYPE payment_audit_event_type AS ENUM (
    'PAYMENT_CREATED',
    'PAYMENT_STATUS_CHANGED',
    'SETTLEMENT_APPLIED'
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_transaction_entity
        WHERE request_fingerprint_version IS NOT NULL
          AND request_fingerprint_version !~ '^v[0-9]+$'
    ) THEN
        RAISE EXCEPTION 'request_fingerprint_version must use the v<number> format';
    END IF;
END
$$;

ALTER TABLE payment_transaction_entity
    DROP CONSTRAINT payment_transaction_rejection_reason_ck;

ALTER TABLE payment_audit_event
    DROP CONSTRAINT payment_audit_event_type_ck,
    DROP CONSTRAINT payment_audit_event_shape_ck,
    DROP CONSTRAINT payment_audit_event_reason_ck;

DROP INDEX uq_payment_audit_created;
DROP INDEX uq_payment_audit_settlement;

ALTER TABLE payment_transaction_entity
    ALTER COLUMN request_fingerprint TYPE BYTEA
        USING CASE
            WHEN request_fingerprint IS NULL THEN NULL
            ELSE decode(request_fingerprint, 'hex')
        END,
    ALTER COLUMN request_fingerprint_version TYPE SMALLINT
        USING CASE
            WHEN request_fingerprint_version IS NULL THEN NULL
            ELSE substring(request_fingerprint_version FROM '^v([0-9]+)$')::SMALLINT
        END,
    ALTER COLUMN status TYPE payment_status
        USING status::TEXT::payment_status,
    ALTER COLUMN rejection_reason TYPE payment_rejection_reason
        USING rejection_reason::payment_rejection_reason;

ALTER TABLE payment_transaction_entity
    ADD CONSTRAINT payment_transaction_fingerprint_length_ck CHECK (
        request_fingerprint IS NULL
        OR octet_length(request_fingerprint) = 32
    ),
    ADD CONSTRAINT payment_transaction_fingerprint_version_ck CHECK (
        request_fingerprint_version IS NULL
        OR request_fingerprint_version >= 0
    ),
    ADD CONSTRAINT payment_transaction_rejection_reason_ck CHECK (
        rejection_reason IS NULL
        OR (
            status = 'REJECTED'
            AND rejection_reason = 'INSUFFICIENT_FUNDS'
        )
    );

ALTER TABLE payment_audit_event
    ALTER COLUMN event_type TYPE payment_audit_event_type
        USING event_type::payment_audit_event_type,
    ALTER COLUMN previous_status TYPE payment_status
        USING previous_status::payment_status,
    ALTER COLUMN resulting_status TYPE payment_status
        USING resulting_status::payment_status,
    ALTER COLUMN reason TYPE payment_rejection_reason
        USING reason::payment_rejection_reason;

ALTER TABLE payment_audit_event
    ADD CONSTRAINT payment_audit_event_shape_ck CHECK (
        (
            event_type = 'PAYMENT_CREATED'
            AND previous_status IS NULL
            AND resulting_status IN ('WAITING_ACCEPTANCE', 'REJECTED')
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
    ),
    ADD CONSTRAINT payment_audit_event_reason_ck CHECK (
        reason IS NULL
        OR (
            event_type IN ('PAYMENT_CREATED', 'PAYMENT_STATUS_CHANGED')
            AND resulting_status = 'REJECTED'
            AND reason = 'INSUFFICIENT_FUNDS'
        )
    );

CREATE UNIQUE INDEX uq_payment_audit_created
    ON payment_audit_event (payment_id)
    WHERE event_type = 'PAYMENT_CREATED';

CREATE UNIQUE INDEX uq_payment_audit_settlement
    ON payment_audit_event (payment_id)
    WHERE event_type = 'SETTLEMENT_APPLIED';
