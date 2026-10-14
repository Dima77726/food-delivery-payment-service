package com.dima.fooddelivery.payment.fx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Конвертер по курсам ЦБ: округление, кэш и поведение при недоступном ЦБ.
 */
class CbrCurrencyConverterTest {

    private static final Duration CACHE = Duration.ofHours(6);
    private static final Duration STALE = Duration.ofHours(72);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T09:00:00Z"));
    private final AtomicInteger fetches = new AtomicInteger();
    private boolean cbrDown;

    private final Function<LocalDate, Map<String, BigDecimal>> source = date -> {
        fetches.incrementAndGet();

        if (cbrDown) {
            throw new RatesUnavailableException("ЦБ недоступен");
        }

        return Map.of("USD", new BigDecimal("83.8675"), "EUR", new BigDecimal("93.7723"));
    };

    private final CbrCurrencyConverter converter = new CbrCurrencyConverter(source, CACHE, STALE, clock);

    @Test
    void shouldConvertAndRoundUpToTheCent() {
        // 900.20 / 83.8675 = 10.7335…: вверх до 10.74, а не вниз до 10.73.
        CurrencyConverter.Conversion conversion = converter.fromRoubles(new BigDecimal("900.20"), "usd");

        assertAll(
                () -> assertEquals(new BigDecimal("10.74"), conversion.amount()),
                () -> assertEquals(new BigDecimal("83.8675"), conversion.rate()),
                () -> assertEquals(LocalDate.of(2026, 10, 14), conversion.fetchedFrom())
        );
    }

    @Test
    void shouldNotRoundUpAnExactAmount() {
        CbrCurrencyConverter exact = new CbrCurrencyConverter(
                date -> Map.of("USD", new BigDecimal("80")), CACHE, STALE, clock);

        // 800 / 80 ровно 10.00: лишней копейки быть не должно.
        assertEquals(new BigDecimal("10.00"), exact.fromRoubles(new BigDecimal("800.00"), "USD").amount());
    }

    @Test
    void shouldFetchOnceAndServeFromCacheWhileFresh() {
        converter.fromRoubles(new BigDecimal("100"), "USD");
        converter.fromRoubles(new BigDecimal("200"), "EUR");

        assertEquals(1, fetches.get());
    }

    @Test
    void shouldRefreshAfterCacheExpires() {
        converter.fromRoubles(new BigDecimal("100"), "USD");

        clock.advance(CACHE.plusMinutes(1));
        converter.fromRoubles(new BigDecimal("100"), "USD");

        assertEquals(2, fetches.get());
    }

    /** ЦБ лёг, а набору чуть больше срока свежести: пользуемся им, а не останавливаем оплату. */
    @Test
    void shouldUseStaleRatesWhileCbrIsDownWithinStaleLimit() {
        converter.fromRoubles(new BigDecimal("100"), "USD");

        clock.advance(CACHE.plusHours(1));
        cbrDown = true;

        assertEquals(new BigDecimal("1.20"), converter.fromRoubles(new BigDecimal("100"), "USD").amount());
    }

    /** Курс недельной давности — уже не курс: без свежего значения оплата не проходит. */
    @Test
    void shouldRefuseWhenRatesAreTooStaleAndCbrIsDown() {
        converter.fromRoubles(new BigDecimal("100"), "USD");

        clock.advance(STALE.plusHours(1));
        cbrDown = true;

        assertThrows(RatesUnavailableException.class, () -> converter.fromRoubles(new BigDecimal("100"), "USD"));
    }

    @Test
    void shouldFailWhenNothingIsCachedAndCbrIsDown() {
        cbrDown = true;

        assertThrows(RatesUnavailableException.class, () -> converter.fromRoubles(new BigDecimal("100"), "USD"));
    }

    @Test
    void shouldRefuseCurrencyThatCbrDoesNotPublish() {
        assertThrows(RatesUnavailableException.class, () -> converter.fromRoubles(new BigDecimal("100"), "XXX"));
    }

    /** Одновременные платежи при пустом кэше делают один запрос к ЦБ, а не по запросу на платёж. */
    @Test
    void shouldFetchOnceForConcurrentFirstPayments() throws Exception {
        Thread[] threads = new Thread[8];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> converter.fromRoubles(new BigDecimal("100"), "USD"));
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, fetches.get());
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
