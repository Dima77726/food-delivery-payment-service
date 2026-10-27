package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ссылка на платёж у провайдера: сохраняется вместе с платежом и доезжает до возврата.
 *
 * <p>Шлюз здесь подменён записывающим: нужен не Stripe, а то, что сервис делает с ответом шлюза.
 * Возврат без сохранённой ссылки у настоящего провайдера не выполнить — деньги вернуть можно
 * только по платежу, а не по заказу, — поэтому потеря ссылки между списанием и возвратом
 * была бы тихой и дорогой ошибкой.
 */
class PaymentProviderReferenceIT extends AbstractIntegrationTest {

    private static final AtomicLong ORDER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 5_000_000_000L);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RecordingGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void reset() {
        gateway.reset();
    }

    @Test
    void shouldStoreReferenceAndPassIdempotencyKeyToGateway() {
        Long orderId = ORDER_IDS.incrementAndGet();
        gateway.nextCharge = PaymentService.PaymentGateway.ChargeResult.success("pi_it_1");
        var command = PaymentServiceIT.paymentRequested(orderId, 77L, "450.00");

        paymentService.handle(command, KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals("pi_it_1", referenceOf(orderId)),
                () -> assertEquals(1, gateway.charges.size()),
                () -> assertEquals(command.idempotencyKey(), gateway.charges.get(0).idempotencyKey(),
                        "ключ идемпотентности обязан дойти до провайдера — на нём держится защита от двойного списания")
        );
    }

    /** По номеру неудачной попытки её находят в кабинете провайдера, поэтому он сохраняется и при отказе. */
    @Test
    void shouldStoreReferenceOfDeclinedAttemptToo() {
        Long orderId = ORDER_IDS.incrementAndGet();
        gateway.nextCharge = PaymentService.PaymentGateway.ChargeResult.failure("declined", "pi_it_declined");

        paymentService.handle(PaymentServiceIT.paymentRequested(orderId, 77L, "450.00"), KafkaTopics.PAYMENT_COMMANDS);

        assertEquals("pi_it_declined", referenceOf(orderId));
    }

    @Test
    void shouldRefundByStoredReferenceWithSameIdempotencyKey() {
        Long orderId = ORDER_IDS.incrementAndGet();
        gateway.nextCharge = PaymentService.PaymentGateway.ChargeResult.success("pi_it_3");
        var charge = PaymentServiceIT.paymentRequested(orderId, 77L, "450.00");
        paymentService.handle(charge, KafkaTopics.PAYMENT_COMMANDS);

        paymentService.handle(PaymentServiceIT.refundRequested(orderId, 77L), KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals(1, gateway.refunds.size()),
                () -> assertEquals("pi_it_3", gateway.refunds.get(0).providerReference()),
                () -> assertEquals(charge.idempotencyKey(), gateway.refunds.get(0).idempotencyKey()),
                () -> assertEquals("REFUNDED", statusOf(orderId))
        );
    }

    /** Частичные возвраты копятся, больше списанного не вернуть, а вернули всё — платёж REFUNDED. */
    @Test
    void shouldRefundPartiallyUpToChargedAmount() {
        Long orderId = ORDER_IDS.incrementAndGet();
        gateway.nextCharge = PaymentService.PaymentGateway.ChargeResult.success("pi_it_partial");
        paymentService.handle(PaymentServiceIT.paymentRequested(orderId, 77L, "450.00"), KafkaTopics.PAYMENT_COMMANDS);

        paymentService.handle(partialRefund(orderId, "150.00"), KafkaTopics.PAYMENT_COMMANDS);
        assertEquals("SUCCEEDED", statusOf(orderId));
        paymentService.handle(partialRefund(orderId, "400.00"), KafkaTopics.PAYMENT_COMMANDS);   // больше остатка
        paymentService.handle(partialRefund(orderId, "300.00"), KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertEquals(2, gateway.refunds.size(), "возврат сверх остатка не должен дойти до провайдера"),
                () -> assertEquals(new java.math.BigDecimal("150.00"), gateway.refunds.get(0).amount()),
                () -> assertTrue(gateway.refunds.get(0).idempotencyKey().startsWith("partial-refund:")),
                () -> assertEquals("REFUNDED", statusOf(orderId))
        );
    }

    private static com.dima.fooddelivery.contracts.payment.PaymentCommand partialRefund(Long orderId, String amount) {
        return new com.dima.fooddelivery.contracts.payment.PaymentCommand(java.util.UUID.randomUUID(),
                com.dima.fooddelivery.contracts.payment.PaymentCommand.CURRENT_SCHEMA_VERSION,
                com.dima.fooddelivery.contracts.payment.PaymentCommand.REFUND_REQUESTED, orderId, 77L,
                new java.math.BigDecimal(amount), null, "partial-refund:" + java.util.UUID.randomUUID(), java.time.OffsetDateTime.now());
    }

    /** Платёж, которого провайдер не видел (наличные, заглушка, до миграции), возвращается без ссылки. */
    @Test
    void shouldRefundWithoutReferenceWhenProviderNeverSawThePayment() {
        Long orderId = ORDER_IDS.incrementAndGet();
        gateway.nextCharge = PaymentService.PaymentGateway.ChargeResult.success();
        paymentService.handle(PaymentServiceIT.paymentRequested(orderId, 77L, "450.00"), KafkaTopics.PAYMENT_COMMANDS);

        paymentService.handle(PaymentServiceIT.refundRequested(orderId, 77L), KafkaTopics.PAYMENT_COMMANDS);

        assertAll(
                () -> assertNull(referenceOf(orderId)),
                () -> assertEquals(1, gateway.refunds.size()),
                () -> assertNull(gateway.refunds.get(0).providerReference())
        );
    }

    /**
     * Сброс изменений в базу перед чтением через JDBC. Тест идёт в транзакции без коммита, а
     * автоматический сброс Hibernate срабатывает только перед его собственными запросами:
     * {@code JdbcTemplate} увидел бы ещё не записанную строку. В бою запись делает коммит.
     */
    private String referenceOf(Long orderId) {
        entityManager.flush();

        return jdbcTemplate.queryForObject(
                "SELECT provider_reference FROM payment WHERE order_id = ?", String.class, orderId
        );
    }

    private String statusOf(Long orderId) {
        entityManager.flush();

        return jdbcTemplate.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
    }

    @TestConfiguration
    static class RecordingGatewayConfig {

        @Bean
        @Primary
        RecordingGateway recordingGateway() {
            return new RecordingGateway();
        }
    }

    /** Шлюз, который отвечает заданным исходом и запоминает, с чем его вызвали. */
    static class RecordingGateway implements PaymentService.PaymentGateway {

        final List<ChargeRequest> charges = new ArrayList<>();
        final List<RefundRequest> refunds = new ArrayList<>();
        volatile ChargeResult nextCharge = ChargeResult.success();

        void reset() {
            charges.clear();
            refunds.clear();
            nextCharge = ChargeResult.success();
        }

        @Override
        public ChargeResult charge(ChargeRequest request) {
            charges.add(request);

            return nextCharge;
        }

        @Override
        public void refund(RefundRequest request) {
            refunds.add(request);
        }
    }
}
