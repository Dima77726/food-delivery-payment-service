package com.dima.fooddelivery.payment.persistence;

import java.util.UUID;

/**
 * Строка outbox в том виде, в каком её забирает публикатор.
 *
 * <p>Тело события остаётся строкой JSON и здесь не разбирается: публикатору всё равно,
 * что внутри — его дело доставить байты в Kafka. Разбор — забота потребителя.
 */
public record OutboxRecord(
        Long id,
        UUID eventId,
        String topic,
        Long aggregateId,
        String eventType,
        String payload,
        int attempts,

        /**
         * Метка запроса, в котором произошло событие. Едет отдельным полем, а не внутри
         * тела: тело — это контракт для потребителей, а метка относится к доставке
         * и в Kafka уходит заголовком, как и положено служебным данным.
         */
        String correlationId,

        /** Контекст трассы запроса (W3C traceparent) или null, если запрос не трассировался. */
        String traceparent
) {
}
