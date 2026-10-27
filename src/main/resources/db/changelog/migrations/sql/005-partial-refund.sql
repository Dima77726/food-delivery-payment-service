-- Частичные возвраты: сколько уже вернули по платежу. Полный возврат по-прежнему переводит статус в REFUNDED,
-- частичные копятся здесь; дошли до суммы платежа — тоже REFUNDED.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS refunded_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE payment DROP CONSTRAINT IF EXISTS chk_payment_refunded_amount;
ALTER TABLE payment ADD CONSTRAINT chk_payment_refunded_amount CHECK (refunded_amount >= 0 AND refunded_amount <= amount);
