package com.dima.fooddelivery.payment.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Шлюз Stripe в настоящем контексте Spring: собирается ли он из настроек и бинов приложения.
 *
 * <p>Юнит-тест шлюза создаёт его вручную и такого не увидит. Этот тест появился после того, как
 * включённый Stripe не смог создаться: шлюз требовал внедрённый {@code RestClient.Builder}, а в
 * сервисе его нет (в Spring Boot 4 автонастройка вынесена в отдельный стартер). В обычных
 * интеграционных тестах шлюз не создаётся вообще — он включается настройкой, — поэтому ошибка
 * дожила бы до первого запуска со Stripe. Контекст здесь минимальный, без базы и контейнеров:
 * только то, что нужно шлюзу.
 */
class StripePaymentGatewayWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(StripePaymentGateway.class);

    @Test
    void shouldCreateStripeGatewayWhenEnabledWithKey() {
        runner.withPropertyValues(
                "app.payments.gateway=stripe",
                "app.payments.stripe.api-key=sk_test_wiring"
        ).run(context -> assertAll(
                () -> assertNull(context.getStartupFailure(), "контекст со Stripe должен подниматься"),
                () -> assertNotNull(context.getBean(StripePaymentGateway.class))
        ));
    }

    @Test
    void shouldNotCreateStripeGatewayByDefault() {
        runner.run(context -> assertEquals(0, context.getBeansOfType(StripePaymentGateway.class).size()));
    }

    /** Включённый Stripe без ключа — отказ при старте, а не первая оплата, упавшая у клиента. */
    @Test
    void shouldFailToStartWithoutApiKey() {
        runner.withPropertyValues("app.payments.gateway=stripe")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
