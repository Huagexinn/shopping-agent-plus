-- 与购物车修改同事务提交，数据库唯一约束是最终幂等边界。
-- 若已有手工创建的同名表，应先核对并纳入迁移，不能静默跳过唯一约束。
CREATE TABLE cart_operation_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    operation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY unique_cart_operations_user_operation (user_id, operation_id),
    KEY idx_cart_operations_created_at (created_at),
    CONSTRAINT chk_cart_operations_user CHECK (user_id > 0),
    CONSTRAINT chk_cart_operations_state CHECK (
        (status = 'PROCESSING' AND result_json IS NULL)
        OR (status = 'SUCCEEDED' AND result_json IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
