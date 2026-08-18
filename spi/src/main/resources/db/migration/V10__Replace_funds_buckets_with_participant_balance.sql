DROP TABLE funds_bucket_entity;
DROP TABLE funds_entity;

CREATE TABLE participant_balance_entity (
    bank_code TEXT PRIMARY KEY,
    balance_cents BIGINT NOT NULL,
    CONSTRAINT participant_balance_non_negative_ck CHECK (balance_cents >= 0)
);

ALTER TABLE payment_audit_event
    DROP CONSTRAINT payment_audit_event_shape_ck,
    DROP CONSTRAINT payment_audit_event_reason_ck;

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
