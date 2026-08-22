DROP INDEX idx_payment_audit_event_payment;

ALTER TABLE payment_audit_event
    DROP CONSTRAINT payment_audit_event_pkey;
