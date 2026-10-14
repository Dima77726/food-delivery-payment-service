package com.dima.fooddelivery.payment.fx;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Пересчёт рублёвой суммы заказа в валюту провайдера.
 */
public interface CurrencyConverter {

    /**
     * @param roubles        сумма заказа в рублях
     * @param targetCurrency трёхбуквенный код валюты провайдера (USD)
     * @throws RatesUnavailableException если курса нет и взять его неоткуда
     */
    Conversion fromRoubles(BigDecimal roubles, String targetCurrency);

    /**
     * @param amount      сумма в валюте провайдера, округлённая до двух знаков
     * @param rate        рублей за единицу валюты
     * @param fetchedFrom дата, на которую запрошен курс
     */
    record Conversion(BigDecimal amount, BigDecimal rate, LocalDate fetchedFrom) {
    }
}
