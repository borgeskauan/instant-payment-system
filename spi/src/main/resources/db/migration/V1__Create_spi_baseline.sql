CREATE TYPE payment_state AS ENUM (
    'WAITING_ACCEPTANCE',
    'SETTLED',
    'REJECTED'
);

CREATE TYPE payment_rejection_cause AS ENUM (
    'INSUFFICIENT_FUNDS'
);

CREATE TYPE payment_audit_event_type AS ENUM (
    'PAYMENT_RESERVED',
    'PAYMENT_SETTLED',
    'PAYMENT_REJECTED'
);

CREATE TABLE payment_transaction_entity (
    payment_id VARCHAR(255) PRIMARY KEY,
    currency VARCHAR(3),
    description VARCHAR(255),
    state payment_state NOT NULL,

    sender_name VARCHAR(255),
    sender_tax_id VARCHAR(255),
    sender_pix_key VARCHAR(255),
    sender_account_number VARCHAR(255),
    sender_account_branch VARCHAR(255),
    sender_account_type VARCHAR(50),
    sender_bank_code VARCHAR(50),

    receiver_name VARCHAR(255),
    receiver_tax_id VARCHAR(255),
    receiver_pix_key VARCHAR(255),
    receiver_account_number VARCHAR(255),
    receiver_account_branch VARCHAR(255),
    receiver_account_type VARCHAR(50),
    receiver_bank_code VARCHAR(50),

    amount_cents BIGINT,
    request_fingerprint BYTEA,
    request_fingerprint_version SMALLINT,
    rejection_cause payment_rejection_cause,
    external_reason_codes TEXT[],

    CONSTRAINT payment_transaction_fingerprint_length_ck CHECK (
        request_fingerprint IS NULL
        OR octet_length(request_fingerprint) = 32
    ),
    CONSTRAINT payment_transaction_fingerprint_version_ck CHECK (
        request_fingerprint_version IS NULL
        OR request_fingerprint_version >= 0
    ),
    CONSTRAINT payment_transaction_rejection_origin_ck CHECK (
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
    )
) WITH (fillfactor = 50);

CREATE TABLE participant_balance_entity (
    bank_code TEXT PRIMARY KEY,
    balance_cents BIGINT NOT NULL,
    CONSTRAINT participant_balance_non_negative_ck CHECK (balance_cents >= 0)
);

CREATE TABLE notification_outbox (
    communication_id TEXT PRIMARY KEY,
    recipient_ispb TEXT NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_audit_event (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    payment_id TEXT NOT NULL,
    event_type payment_audit_event_type NOT NULL,
    previous_state payment_state,
    resulting_state payment_state,
    amount_cents BIGINT,
    sender_ispb TEXT,
    receiver_ispb TEXT,
    sender_delta_cents BIGINT,
    receiver_delta_cents BIGINT,
    rejection_cause payment_rejection_cause,
    external_reason_codes TEXT[],
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT payment_audit_event_shape_ck CHECK (
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
    )
);

CREATE UNIQUE INDEX uq_payment_audit_admission
    ON payment_audit_event (payment_id)
    WHERE previous_state IS NULL;

CREATE UNIQUE INDEX uq_payment_audit_terminal_outcome
    ON payment_audit_event (payment_id)
    WHERE previous_state = 'WAITING_ACCEPTANCE';
