ALTER TABLE payment_transaction_entity
    ADD COLUMN rejection_reason TEXT;

ALTER TABLE payment_transaction_entity
    ADD CONSTRAINT payment_transaction_rejection_reason_ck CHECK (
        rejection_reason IS NULL
        OR (
            status = 'REJECTED'
            AND rejection_reason = 'INSUFFICIENT_FUNDS'
        )
    );

ALTER TABLE payment_audit_event
    ADD COLUMN reason TEXT;

ALTER TABLE payment_audit_event
    ADD CONSTRAINT payment_audit_event_reason_ck CHECK (
        reason IS NULL
        OR (
            event_type = 'PAYMENT_STATUS_CHANGED'
            AND resulting_status = 'REJECTED'
            AND reason = 'INSUFFICIENT_FUNDS'
        )
    );
