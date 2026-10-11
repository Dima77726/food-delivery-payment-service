package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.contracts.payment.PaymentCommand;
import com.dima.fooddelivery.payment.api.PaymentResponse;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Исполнение команд саги: что записано в платежи и какой исход лёг в outbox.
 *
 * <p>Отказ банка воспроизводится детерминированно: {@link SimulatedPaymentGateway} отклоняет
 * сумму, оканчивающуюся на {@code .13}.
 */
class PaymentServiceIT extends AbstractIntegrationTest {

    private static final AtomicLong ORDER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldChargeAndPublishSucceeded() {
        Long orderId = ORDER_IDS.incrementAndGet();

        paymentService.handle(paymentRequested(orderId, 77L, "450.00"), KafkaTopics.PAYMENT_COMMANDS);

        List<PaymentResponse> payments = paymentService.getPaymentsForOrder(77L, orderId);

        assertAll(
                () -> assertEquals(1, payments.size()),
                () -> assertEquals(PaymentStatus.SUCCEEDED, payments.get(0).status()),
                () -> assertEquals(new BigDecimal("450.00"), payments.get(0).amount()),
                () -> assertEquals(List.of("PAYMENT_SUCCEEDED"), outcomes(orderId))
        );
    }

    @Test
    void shouldRecordDeclineAndPublishFailedWithReason() {
        Long orderId = ORDER_IDS.incrementAndGet();

        paymentService.handle(paymentRequested(orderId, 77L, "100.13"), KafkaTopics.PAYMENT_COMMANDS);

        PaymentResponse payment = paymentService.getPaymentsForOrder(77L, orderId).get(0);

        assertAll(
                () -> assertEquals(PaymentStatus.FAILED, payment.status()),
                () -> assertEquals("Недостаточно средств на карте", payment.failureReason()),
                () -> assertEquals(List.of("PAYMENT_FAILED"), outcomes(orderId)),
                () -> assertEquals(
                        "Недостаточно средств на карте",
                        outcomeField(orderId, "PAYMENT_FAILED", "failureReason")
                )
        );
    }

    /** Повтор той же команды — как после сбоя до коммита офсета: второго списания нет. */
    @Test
    void shouldIgnoreRedeliveredCommand() {
        Long orderId = ORDER_IDS.incrementAndGet();
        PaymentCommand command = paymentRequested(orderId, 77L, "450.00");

        paymentService.handle(command, KafkaTopics.PAYMENT_COMMANDS);
        paymentService.handle(command, KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals(1, paymentService.getPaymentsForOrder(77L, orderId).size()),
                () -> assertEquals(1, outcomes(orderId).size())
        );
    }

    /**
     * Вторая команда на ту же оплату (новый идентификатор, тот же ключ): деньги не списываются
     * второй раз, а исход повторяется — первый мог не дойти.
     */
    @Test
    void shouldRepeatOutcomeForSecondCommandWithSameIdempotencyKey() {
        Long orderId = ORDER_IDS.incrementAndGet();
        PaymentCommand first = paymentRequested(orderId, 77L, "450.00");
        PaymentCommand second = new PaymentCommand(
                UUID.randomUUID(), first.schemaVersion(), first.commandType(), orderId, 77L,
                first.amount(), first.method(), first.idempotencyKey(), OffsetDateTime.now()
        );

        paymentService.handle(first, KafkaTopics.PAYMENT_COMMANDS);
        paymentService.handle(second, KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals(1, paymentService.getPaymentsForOrder(77L, orderId).size()),
                () -> assertEquals(List.of("PAYMENT_SUCCEEDED", "PAYMENT_SUCCEEDED"), outcomes(orderId))
        );
    }

    @Test
    void shouldRefundSucceededPayment() {
        Long orderId = ORDER_IDS.incrementAndGet();

        paymentService.handle(paymentRequested(orderId, 77L, "450.00"), KafkaTopics.PAYMENT_COMMANDS);
        paymentService.handle(refundRequested(orderId, 77L), KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals(PaymentStatus.REFUNDED, paymentService.getPaymentsForOrder(77L, orderId).get(0).status()),
                () -> assertEquals(List.of("PAYMENT_SUCCEEDED", "PAYMENT_REFUNDED"), outcomes(orderId))
        );
    }

    /** Заказ отменили раньше, чем дошло списание, или оплата не прошла: возвращать нечего. */
    @Test
    void shouldDoNothingOnRefundWithoutSucceededPayment() {
        Long orderId = ORDER_IDS.incrementAndGet();

        paymentService.handle(refundRequested(orderId, 77L), KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals(0, paymentService.getPaymentsForOrder(77L, orderId).size()),
                () -> assertEquals(List.of(), outcomes(orderId))
        );
    }

    @Test
    void shouldNotShowPaymentsOfForeignOrder() {
        Long orderId = ORDER_IDS.incrementAndGet();

        paymentService.handle(paymentRequested(orderId, 77L, "450.00"), KafkaTopics.PAYMENT_COMMANDS);

        assertThrows(
                AccessDeniedForResourceException.class,
                () -> paymentService.getPaymentsForOrder(78L, orderId)
        );
    }

    static PaymentCommand paymentRequested(Long orderId, Long customerId, String amount) {
        return new PaymentCommand(
                UUID.randomUUID(),
                PaymentCommand.CURRENT_SCHEMA_VERSION,
                PaymentCommand.PAYMENT_REQUESTED,
                orderId,
                customerId,
                new BigDecimal(amount),
                "CARD",
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );
    }

    static PaymentCommand refundRequested(Long orderId, Long customerId) {
        return new PaymentCommand(
                UUID.randomUUID(),
                PaymentCommand.CURRENT_SCHEMA_VERSION,
                PaymentCommand.REFUND_REQUESTED,
                orderId,
                customerId,
                new BigDecimal("450.00"),
                null,
                null,
                OffsetDateTime.now()
        );
    }

    private List<String> outcomes(Long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM payment_outbox WHERE aggregate_id = ? ORDER BY id",
                String.class,
                orderId
        );
    }

    private String outcomeField(Long orderId, String eventType, String field) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT CAST(payload AS JSONB) ->> ?
                        FROM payment_outbox
                        WHERE aggregate_id = ? AND event_type = ?
                        """,
                String.class,
                field,
                orderId,
                eventType
        );
    }
}
