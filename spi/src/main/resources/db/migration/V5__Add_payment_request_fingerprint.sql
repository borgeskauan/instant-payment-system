ALTER TABLE payment_transaction_entity
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN request_fingerprint_version VARCHAR(16);
