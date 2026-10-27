-- Входящие вебхуки Stripe: inbox по идентификатору события провайдера (evt_…). Stripe доставляет
-- «хотя бы один раз» и повторяет, пока не получит 2xx, — повтор того же события должен быть пустым
-- действием. Идентификатор Stripe — строка, а не UUID, поэтому таблица своя, а не processed_message.
CREATE TABLE IF NOT EXISTS stripe_webhook_event
(
    event_id    VARCHAR(255) PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    outcome     VARCHAR(200) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Платёж по номеру у провайдера: по нему вебхук находит, о каком платеже речь.
CREATE INDEX IF NOT EXISTS idx_payment_provider_reference
    ON payment (provider_reference) WHERE provider_reference IS NOT NULL;

COMMENT ON TABLE stripe_webhook_event IS 'Обработанные вебхуки Stripe: защита от повторной доставки и журнал исходов';
