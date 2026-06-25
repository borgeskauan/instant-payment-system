ALTER TABLE payment_transaction_entity
    ALTER COLUMN sender_account_number TYPE VARCHAR(255) USING sender_account_number::TEXT,
    ALTER COLUMN sender_account_branch TYPE VARCHAR(255) USING sender_account_branch::TEXT,
    ALTER COLUMN receiver_account_number TYPE VARCHAR(255) USING receiver_account_number::TEXT,
    ALTER COLUMN receiver_account_branch TYPE VARCHAR(255) USING receiver_account_branch::TEXT;
