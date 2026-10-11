package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.payment.domain.Payment;
import com.dima.fooddelivery.payment.persistence.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Кладёт исход платежа в outbox — в той же транзакции, что и сам платёж.
 *
 * <p>Без outbox здесь была бы та же двойная запись, что и у продюсера событий заказа:
 * деньги списаны и записаны, а сообщение об этом не ушло — и заказ навсегда в PAYMENT_PENDING,
 * пока его не отменит таймаут с возвратом уже списанных денег.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void append(String eventType, Payment payment) {
        UUID eventId = UUID.randomUUID();

        PaymentEvent event = new PaymentEvent(
                eventId,
                PaymentEvent.CURRENT_SCHEMA_VERSION,
                eventType,
                payment.getOrderId(),
                payment.getId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getMethod().name(),
                payment.getFailureReason(),
                OffsetDateTime.now()
        );

        // Ключ — заказ, а не платёж: исходы одного заказа обязаны идти по порядку.
        outboxRepository.append(
                eventId,
                KafkaTopics.PAYMENT_EVENTS,
                payment.getOrderId(),
                eventType,
                objectMapper.writeValueAsString(event),
                RequestContext.correlationId().orElse(null)
        );

        log.debug("Исход платежа помещён в outbox: orderId={}, type={}, eventId={}",
                payment.getOrderId(), eventType, eventId);
    }
}
