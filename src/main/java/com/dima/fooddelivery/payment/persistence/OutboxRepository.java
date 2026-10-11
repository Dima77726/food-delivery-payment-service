package com.dima.fooddelivery.payment.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private static final RowMapper<OutboxRecord> OUTBOX_RECORD = (rs, rowNum) -> new OutboxRecord(
            rs.getLong("id"),
            rs.getObject("event_id", UUID.class),
            rs.getString("topic"),
            rs.getLong("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getInt("attempts"),
            rs.getString("correlation_id")
    );

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Кладёт событие в очередь на публикацию.
     *
     * <p>Вызывается внутри транзакции бизнес-операции — в этом весь смысл. Отдельной
     * транзакции здесь быть не должно, иначе вернётся та самая двойная запись,
     * от которой outbox и защищает.
     */
    public void append(
            UUID eventId,
            String topic,
            Long aggregateId,
            String eventType,
            String payload,
            String correlationId
    ) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("topic", topic)
                .addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType)
                .addValue("payload", payload)
                .addValue("correlationId", correlationId);

        jdbc.update(
                """
                        INSERT INTO payment_outbox (
                            event_id, topic, aggregate_id, event_type, payload, correlation_id
                        )
                        VALUES (
                            CAST(:eventId AS UUID), :topic, :aggregateId, :eventType,
                            :payload, :correlationId
                        )
                        """,
                params
        );
    }

    /**
     * Забирает пачку неотправленных событий, блокируя их за собой.
     *
     * <p>Тот же {@code FOR UPDATE SKIP LOCKED}, что и в рассылке уведомлений: несколько
     * экземпляров приложения разбирают очередь параллельно и не пересекаются, внешний
     * координатор не нужен.
     *
     * <p>Порядок по id обязателен. События одного заказа должны уходить в Kafka в том же
     * порядке, в каком произошли, иначе потребитель увидит доставку раньше оплаты.
     * Внутри партиции Kafka порядок сохраняет, но только если мы сами отправляем
     * последовательно.
     */
    public List<OutboxRecord> lockPending(int limit) {
        return jdbc.query(
                """
                        SELECT id, event_id, topic, aggregate_id, event_type, payload,
                               attempts, correlation_id
                        FROM payment_outbox
                        WHERE status = 'PENDING'
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """,
                new MapSqlParameterSource("limit", limit),
                OUTBOX_RECORD
        );
    }

    public int markPublished(Long id) {
        return jdbc.update(
                """
                        UPDATE payment_outbox
                        SET status = 'PUBLISHED',
                            published_at = CURRENT_TIMESTAMP,
                            last_error = NULL
                        WHERE id = :id
                          AND status = 'PENDING'
                        """,
                new MapSqlParameterSource("id", id)
        );
    }

    /**
     * Отмечает неудачную попытку.
     *
     * <p>Запись остаётся в PENDING и будет взята снова: сбой Kafka почти всегда временный.
     * В FAILED она уходит только после {@code maxAttempts} — иначе одно неотправляемое
     * событие блокировало бы очередь навсегда.
     */
    public int recordFailure(Long id, String error, int maxAttempts) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("error", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .addValue("maxAttempts", maxAttempts);

        return jdbc.update(
                """
                        UPDATE payment_outbox
                        SET attempts = attempts + 1,
                            last_error = :error,
                            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
                        WHERE id = :id
                        """,
                params
        );
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox WHERE status = :status",
                new MapSqlParameterSource("status", status),
                Long.class
        );

        return count == null ? 0L : count;
    }
}
