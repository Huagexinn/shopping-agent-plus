ALTER TABLE cart_items
    DROP INDEX unique_cart_items_product,
    ADD COLUMN active_product_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN product_id ELSE NULL END) STORED,
    ADD UNIQUE KEY unique_cart_items_active_product (cart_id, active_product_id);
