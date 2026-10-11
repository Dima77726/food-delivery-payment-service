package com.dima.fooddelivery.payment.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Inbox: какие входящие сообщения уже обработаны.
 *
 * <p>Зеркало outbox на принимающей стороне. Outbox гарантирует, что сообщение уйдёт хотя бы
 * раз, inbox — что оно подействует не больше одного раза. Вместе они дают «ровно один раз»
 * там, где это важно, — в бизнес-эффекте, а не в доставке.
 */
@Repository
@RequiredArgsConstructor
public class InboxRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Отмечает сообщение обработанным. Вызывается в транзакции обработки: откат обработки
     * откатывает и отметку.
     *
     * @return {@code true}, если сообщение встретилось впервые; {@code false} — это повтор
     */
    public boolean markProcessed(UUID messageId, String topic) {
        int inserted = jdbcTemplate.update("""
                        INSERT INTO processed_message (message_id, topic)
                        VALUES (?, ?)
                        ON CONFLICT (message_id) DO NOTHING
                        """,
                messageId, topic);

        return inserted == 1;
    }
}
