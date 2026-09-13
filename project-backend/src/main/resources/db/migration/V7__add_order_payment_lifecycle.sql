-- 引入订单支付生命周期：1 待支付 / 2 已支付 / 3 已关闭。
-- 历史订单在引入支付状态机前没有支付概念，统一回填为已支付，
-- 避免后续关单任务释放这些订单已经占用的库存。
-- 执行顺序不可调整：必须先删除旧约束，回填才能写入 status = 2。
ALTER TABLE orders DROP CHECK chk_orders_status;

ALTER TABLE orders
    ADD COLUMN expires_at DATETIME NULL AFTER total_amount,
    ADD COLUMN paid_at DATETIME NULL AFTER expires_at,
    ADD COLUMN closed_at DATETIME NULL AFTER paid_at,
    ADD COLUMN close_reason VARCHAR(32) NULL AFTER closed_at;

UPDATE orders SET status = 2, paid_at = created_at WHERE status = 1;

UPDATE orders SET expires_at = created_at + INTERVAL 30 MINUTE WHERE expires_at IS NULL;

ALTER TABLE orders
    MODIFY COLUMN expires_at DATETIME NOT NULL,
    ADD CONSTRAINT chk_orders_status CHECK (status IN (1, 2, 3)),
    ADD CONSTRAINT chk_orders_paid_at CHECK ((status = 2) = (paid_at IS NOT NULL)),
    ADD CONSTRAINT chk_orders_closed_at CHECK ((status = 3) = (closed_at IS NOT NULL)),
    ADD CONSTRAINT chk_orders_close_reason CHECK (
        (status = 3 AND close_reason IN ('USER_CANCELLED', 'PAYMENT_TIMEOUT'))
        OR (status <> 3 AND close_reason IS NULL)),
    ADD KEY idx_orders_status_expires_at (status, expires_at);
