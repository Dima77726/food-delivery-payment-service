package com.dima.fooddelivery.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Бизнес-метрики платежей.
 *
 * <p>Имя и теги метрики — прежние, из монолита: {@code food_delivery.payments} с тегами
 * {@code method} и {@code outcome}. Дашборд «доля отказов» в Grafana строится по ним и после
 * переезда продолжает работать без правок.
 *
 * <p>Один счётчик с тегом исхода, а не три счётчика: доля отказов выражается одним запросом
 * к Prometheus, без сопоставления независимых рядов. Значения тегов — из короткого
 * фиксированного набора, иначе каждое новое значение заводило бы новый временной ряд.
 */
@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    private static final String PAYMENTS = "food_delivery.payments";

    private final MeterRegistry registry;

    public void paymentSucceeded(String method) {
        payment(method, "succeeded");
    }

    public void paymentFailed(String method) {
        payment(method, "failed");
    }

    public void paymentRefunded(String method) {
        payment(method, "refunded");
    }

    private void payment(String method, String outcome) {
        Counter.builder(PAYMENTS)
                .description("Попытки оплаты по исходу и способу")
                .tag("method", method)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
