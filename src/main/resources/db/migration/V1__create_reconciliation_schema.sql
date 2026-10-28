CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tracking_number VARCHAR(64) NOT NULL,
    customer_name VARCHAR(160) NOT NULL,
    expected_amount DECIMAL(15, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_tracking_number UNIQUE (tracking_number),
    INDEX idx_orders_customer_name (customer_name),
    INDEX idx_orders_status (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reconciliation_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_name VARCHAR(255) NOT NULL,
    uploaded_at TIMESTAMP(6) NOT NULL,
    total_rows INT NOT NULL,
    successful_matches INT NOT NULL,
    mismatches INT NOT NULL,
    not_found INT NOT NULL,
    errors INT NOT NULL,
    CONSTRAINT pk_reconciliation_batches PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reconciliation_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    row_num INT NOT NULL,
    tracking_number VARCHAR(64),
    expected_amount DECIMAL(15, 2),
    collected_amount DECIMAL(15, 2),
    delivery_date DATE,
    outcome VARCHAR(32) NOT NULL,
    message VARCHAR(500),
    CONSTRAINT pk_reconciliation_items PRIMARY KEY (id),
    CONSTRAINT fk_reconciliation_items_batch
        FOREIGN KEY (batch_id) REFERENCES reconciliation_batches (id),
    INDEX idx_reconciliation_items_batch_id (batch_id)
) ENGINE=InnoDB;
