-- Create PAYMENT_TRANSACTION_ENTITY table
CREATE TABLE payment_transaction_entity (
    payment_id VARCHAR(255) PRIMARY KEY,
    amount DECIMAL(19,2),
    currency VARCHAR(3),
    description VARCHAR(255),
    status VARCHAR(50),

    -- Sender fields
    sender_name VARCHAR(255),
    sender_tax_id VARCHAR(255),
    sender_pix_key VARCHAR(255),
    sender_account_number BIGINT,
    sender_account_branch INT,
    sender_account_type VARCHAR(50),
    sender_bank_code VARCHAR(50),

    -- Receiver fields
    receiver_name VARCHAR(255),
    receiver_tax_id VARCHAR(255),
    receiver_pix_key VARCHAR(255),
    receiver_account_number BIGINT,
    receiver_account_branch INT,
    receiver_account_type VARCHAR(50),
    receiver_bank_code VARCHAR(50)
);

-- Create DICT_ENTITY table
CREATE TABLE dict_entity (
    id VARCHAR(255) PRIMARY KEY,
    pix_key VARCHAR(255),
    key_type VARCHAR(50),
    creation_date TIMESTAMP,
    key_ownership_date TIMESTAMP,
    account_participant VARCHAR(255),
    account_branch VARCHAR(50),
    account_number VARCHAR(50),
    account_type VARCHAR(50),
    account_opening_date TIMESTAMP,
    owner_type VARCHAR(50),
    owner_tax_id_number VARCHAR(255),
    owner_name VARCHAR(255)
);

-- Create FUNDS_ENTITY table
CREATE TABLE funds_entity (
    bank_code VARCHAR(255) PRIMARY KEY,
    balance DECIMAL(19,2)
);