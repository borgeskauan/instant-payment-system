CREATE TABLE funds_bucket_entity (
    bank_code VARCHAR(255) NOT NULL,
    bucket_id INT NOT NULL,
    balance DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (bank_code, bucket_id)
);
