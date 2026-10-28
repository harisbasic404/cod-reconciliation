ALTER TABLE orders
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

ALTER TABLE reconciliation_batches
    MODIFY COLUMN uploaded_at DATETIME(6) NOT NULL;
