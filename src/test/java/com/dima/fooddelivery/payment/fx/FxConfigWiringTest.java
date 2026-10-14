package com.dima.fooddelivery.payment.fx;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Бины пересчёта по курсу появляются только по флагу. Условные бины не загружаются интеграционными
 * тестами, поэтому поломка их подключения осталась бы незамеченной до первого запуска с флагом.
 */
class FxConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(FxConfig.class));

    @Test
    void shouldCreateConverterWhenEnabled() {
        runner.withPropertyValues("app.fx.enabled=true", "app.fx.cbr-url=http://localhost:1/none")
                .run(context -> assertThat(context).hasSingleBean(CurrencyConverter.class));
    }

    @Test
    void shouldNotCreateAnythingByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CurrencyConverter.class));
    }

    @Test
    void shouldNotCreateWhenExplicitlyDisabled() {
        runner.withPropertyValues("app.fx.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CurrencyConverter.class));
    }
}
