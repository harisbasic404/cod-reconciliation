ALTER TABLE reconciliation_batches
    ADD COLUMN content_hash VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_reconciliation_batches_content_hash
    ON reconciliation_batches (content_hash);
