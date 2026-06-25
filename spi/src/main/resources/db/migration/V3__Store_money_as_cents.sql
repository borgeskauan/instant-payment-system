ALTER TABLE payment_transaction_entity
    ADD COLUMN amount_cents BIGINT;

UPDATE payment_transaction_entity
SET amount_cents = CASE
    WHEN amount IS NULL THEN NULL
    ELSE ROUND(amount * 100)::BIGINT
END
WHERE amount_cents IS NULL;

ALTER TABLE payment_transaction_entity
    DROP COLUMN amount;

ALTER TABLE funds_entity
    ADD COLUMN balance_cents BIGINT;

UPDATE funds_entity
SET balance_cents = CASE
    WHEN balance IS NULL THEN NULL
    ELSE ROUND(balance * 100)::BIGINT
END
WHERE balance_cents IS NULL;

ALTER TABLE funds_entity
    DROP COLUMN balance;

ALTER TABLE funds_bucket_entity
    ADD COLUMN balance_cents BIGINT;

UPDATE funds_bucket_entity
SET balance_cents = ROUND(balance * 100)::BIGINT
WHERE balance_cents IS NULL;

ALTER TABLE funds_bucket_entity
    ALTER COLUMN balance_cents SET NOT NULL;

ALTER TABLE funds_bucket_entity
    DROP COLUMN balance;
