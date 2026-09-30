-- Contract51: apply explicitly after SQL50; no startup migration or production execution.
-- MySQL DDL is not fully transactional. Inspect existing indexes before applying once.
ALTER TABLE aftersale_case
 ADD KEY idx_aftersale_user_created (user_id, created_at, id),
 ADD KEY idx_aftersale_merchant_store_created (merchant_id, store_id, created_at, id);
