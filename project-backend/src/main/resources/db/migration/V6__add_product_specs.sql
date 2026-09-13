-- 为手机商品补充结构化规格，0 表示历史数据尚未录入完整规格。
ALTER TABLE products
    ADD COLUMN ram_gb INT NOT NULL DEFAULT 0 AFTER stock,
    ADD COLUMN storage_gb INT NOT NULL DEFAULT 0 AFTER ram_gb,
    ADD CONSTRAINT chk_products_ram_gb_non_negative CHECK (ram_gb >= 0),
    ADD CONSTRAINT chk_products_storage_gb_non_negative CHECK (storage_gb >= 0),
    ADD KEY idx_products_ram_gb (ram_gb),
    ADD KEY idx_products_storage_gb (storage_gb);
