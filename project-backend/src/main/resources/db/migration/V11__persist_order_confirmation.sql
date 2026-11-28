CREATE TABLE order_drafts (
    draft_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    snapshot_json JSON NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED',
    confirmation_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    order_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    confirmed_at DATETIME(6) NULL,
    UNIQUE KEY uk_draft_confirmation (user_id, confirmation_key),
    UNIQUE KEY uk_draft_order (order_id),
    KEY idx_draft_user_created (user_id, created_at),
    CONSTRAINT fk_draft_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_draft_state CHECK (
        (status IN ('PREVIEWED','CANCELLED') AND order_id IS NULL AND confirmed_at IS NULL)
        OR (status='CONFIRMED' AND order_id IS NOT NULL AND confirmation_key IS NOT NULL AND confirmed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
