-- Apply once to an existing database, after checking duplicates. Does not drop tables/data.
SELECT user_id,voucher_id,COUNT(*) FROM tb_voucher_order GROUP BY user_id,voucher_id HAVING COUNT(*)>1;
SELECT user_id,follow_user_id,COUNT(*) FROM tb_follow GROUP BY user_id,follow_user_id HAVING COUNT(*)>1;
-- If either query returns rows, resolve them manually before running the ALTER statements.
ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher (user_id,voucher_id);
ALTER TABLE tb_follow ADD UNIQUE KEY uk_user_follow (user_id,follow_user_id);
