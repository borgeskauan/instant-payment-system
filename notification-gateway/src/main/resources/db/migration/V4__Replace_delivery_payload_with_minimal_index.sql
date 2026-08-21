DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM notification_delivery LIMIT 1) THEN
        RAISE EXCEPTION
            'notification_delivery must be empty before migrating to delivery_index';
    END IF;
END
$$;

DROP TABLE notification_delivery;
DROP SEQUENCE IF EXISTS notification_delivery_position_seq;

CREATE TABLE delivery_index (
    communication_id TEXT PRIMARY KEY,
    recipient_ispb TEXT NOT NULL,
    delivery_position BIGINT NOT NULL,

    CONSTRAINT delivery_index_position_positive
        CHECK (delivery_position > 0),
    CONSTRAINT delivery_index_recipient_position_key
        UNIQUE (recipient_ispb, delivery_position)
);
