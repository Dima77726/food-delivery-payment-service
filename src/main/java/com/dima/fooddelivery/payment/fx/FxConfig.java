package com.dima.fooddelivery.payment.fx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Пересчёт по курсу ЦБ РФ: бины появляются только при {@code app.fx.enabled=true}. Без флага платёжный
 * шлюз работает как раньше, и сервису не нужен выход к ЦБ.
 */
@Configuration
@EnableConfigurationProperties(FxProperties.class)
@ConditionalOnProperty(name = "app.fx.enabled", havingValue = "true")
class FxConfig {

    @Bean
    CurrencyConverter currencyConverter(FxProperties properties) {
        CbrRatesClient client = new CbrRatesClient(properties);

        return new CbrCurrencyConverter(client::ratesOn, properties.cacheTtl(), properties.staleTtl(), Clock.systemUTC());
    }
}
