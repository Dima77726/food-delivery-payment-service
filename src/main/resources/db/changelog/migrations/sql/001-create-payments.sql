-- Платежи, outbox исходов и inbox команд — в собственной базе сервиса платежей.
--
-- Таблица payment — итог миграции 019 монолита; типы колонок совпадают один в один, данные
-- переносятся оттуда одноразовой задачей (docker/migrations/payments-from-monolith.sh)
-- с сохранением идентификаторов. Внешнего ключа на заказ больше нет: заказы живут
-- в сервисе заказов, в другой базе.

CREATE TABLE IF NOT EXISTS payment
(
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT         NOT NULL,
    customer_id     BIGINT         NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    status          VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    method          VARCHAR(50)    NOT NULL,
    idempotency_key VARCHAR(100)   NOT NULL,
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_payment_idempotency_key
        UNIQUE (idempotency_key),

    CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED')),

    CONSTRAINT chk_payment_method
        CHECK (method IN ('CARD', 'CASH_ON_DELIVERY')),

    CONSTRAINT chk_payment_amount_positive
        CHECK (amount > 0)
);

COMMENT ON TABLE payment IS 'Платежи по заказам, включая неудачные попытки';
COMMENT ON COLUMN payment.order_id IS 'Заказ в сервисе заказов; внешнего ключа нет — другая база';
COMMENT ON COLUMN payment.idempotency_key IS 'Ключ идемпотентности от клиента; защищает от повторной оплаты';
COMMENT ON COLUMN payment.failure_reason IS 'Причина отказа для статуса FAILED';

-- Успешный платёж у заказа может быть только один.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_succeeded_order
    ON payment (order_id)
    WHERE status = 'SUCCEEDED';

CREATE INDEX IF NOT EXISTS idx_payment_order_id ON payment (order_id);
CREATE INDEX IF NOT EXISTS idx_payment_customer_id ON payment (customer_id);
CREATE INDEX IF NOT EXISTS idx_payment_status ON payment (status);

-- Outbox: исходы платежей, записанные вместе с платежом и ещё не отправленные в Kafka.
CREATE TABLE IF NOT EXISTS payment_outbox
(
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMPTZ,

    CONSTRAINT uq_payment_outbox_event_id
        UNIQUE (event_id),

    CONSTRAINT chk_payment_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

COMMENT ON TABLE payment_outbox IS 'Очередь исходов платежей на публикацию в Kafka';
COMMENT ON COLUMN payment_outbox.aggregate_id IS 'Идентификатор заказа; он же ключ сообщения';

CREATE INDEX IF NOT EXISTS idx_payment_outbox_pending
    ON payment_outbox (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_payment_outbox_failed
    ON payment_outbox (id)
    WHERE status = 'FAILED';

-- Inbox: обработанные команды. Повтор «спиши» не должен списать деньги второй раз.
CREATE TABLE IF NOT EXISTS processed_message
(
    message_id   UUID PRIMARY KEY,
    topic        VARCHAR(200) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE processed_message IS 'Обработанные входящие команды Kafka; защита от повторной доставки';
