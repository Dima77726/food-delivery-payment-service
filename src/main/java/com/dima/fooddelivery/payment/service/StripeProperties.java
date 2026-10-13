package com.dima.fooddelivery.payment.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Настройки шлюза Stripe ({@code app.payments.stripe.*}). Используются только при
 * {@code app.payments.gateway=stripe}.
 *
 * @param apiKey                секретный ключ ({@code sk_test_…} для теста). Значения по умолчанию нет:
 *                              ключ не должен лежать в репозитории
 * @param baseUrl               адрес API; свой — только в тестах, где вместо Stripe отвечает заглушка
 * @param currency              валюта платежа. Stripe не принимает рубли на новых аккаунтах, поэтому
 *                              по умолчанию usd; сумма заказа уходит как есть, без пересчёта курса
 * @param paymentMethod         способ оплаты, которым подтверждается платёж. Карты клиента сервис не
 *                              видит, поэтому здесь тестовая карта Stripe
 * @param declinedPaymentMethod тестовая карта, которую Stripe отклоняет
 * @param declineFraction       копейки, с которыми в тестовом режиме платёж подтверждается отклоняемой
 *                              картой (как у заглушки); пусто — отключено. В боевом режиме не действует
 * @param connectTimeout        сколько ждать соединения со Stripe
 * @param readTimeout           сколько ждать ответа: вызов стоит внутри транзакции и держит соединение с базой
 */
@ConfigurationProperties(prefix = "app.payments.stripe")
public record StripeProperties(
        String apiKey,
        @DefaultValue("https://api.stripe.com") String baseUrl,
        @DefaultValue("usd") String currency,
        @DefaultValue("pm_card_visa") String paymentMethod,
        @DefaultValue("pm_card_chargeDeclined") String declinedPaymentMethod,
        @DefaultValue("0.13") BigDecimal declineFraction,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout
) {
}
