CREATE TABLE IF NOT EXISTS dict_entity (
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
