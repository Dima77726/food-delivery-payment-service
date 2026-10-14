package com.dima.fooddelivery.payment.fx;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Конвертер по курсам ЦБ РФ с кэшем.
 *
 * <p><b>Кэш.</b> ЦБ публикует курсы раз в сутки, поэтому платёж не должен ходить за ними в чужой
 * сервис: набор курсов хранится {@link FxProperties#cacheTtl()}. Одновременные платежи при пустом
 * кэше делают один запрос, а не десять: загрузка идёт под замком.
 *
 * <p><b>Недоступный ЦБ.</b> Пока устаревшему набору не больше {@link FxProperties#staleTtl()}, им
 * пользуются, а в журнал пишется предупреждение: курс вчерашнего дня лучше, чем остановленная оплата.
 * Дальше — {@link RatesUnavailableException}: списывать по курсу недельной давности нельзя.
 *
 * <p>Сумма округляется вверх до копейки целевой валюты: округление вниз ежедневно недобирало бы
 * доли копейки в пользу клиента.
 */
@Slf4j
public class CbrCurrencyConverter implements CurrencyConverter {

    /** Курсы ЦБ устанавливаются по московскому времени, и «сегодня» считается по нему. */
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private static final int MINOR_SCALE = 2;

    private final Function<LocalDate, Map<String, BigDecimal>> source;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Clock clock;

    private volatile Snapshot snapshot;

    CbrCurrencyConverter(
            Function<LocalDate, Map<String, BigDecimal>> source,
            Duration cacheTtl,
            Duration staleTtl,
            Clock clock
    ) {
        this.source = source;
        this.cacheTtl = cacheTtl;
        this.staleTtl = staleTtl;
        this.clock = clock;
    }

    @Override
    public Conversion fromRoubles(BigDecimal roubles, String targetCurrency) {
        String code = targetCurrency.toUpperCase(Locale.ROOT);
        Snapshot rates = currentRates();
        BigDecimal rate = rates.rates().get(code);

        if (rate == null) {
            throw new RatesUnavailableException("ЦБ РФ не публикует курс валюты " + code);
        }

        BigDecimal amount = roubles.divide(rate, MINOR_SCALE, RoundingMode.UP);

        return new Conversion(amount, rate, rates.date());
    }

    private Snapshot currentRates() {
        Snapshot current = snapshot;

        if (current != null && current.isFresh(clock.instant(), cacheTtl)) {
            return current;
        }

        synchronized (this) {
            // Пока ждали замок, другой поток мог уже обновить набор.
            current = snapshot;

            if (current != null && current.isFresh(clock.instant(), cacheTtl)) {
                return current;
            }

            try {
                LocalDate today = LocalDate.now(clock.withZone(MOSCOW));
                Snapshot loaded = new Snapshot(source.apply(today), today, clock.instant());
                snapshot = loaded;

                return loaded;
            } catch (RatesUnavailableException failure) {
                if (current != null && current.isFresh(clock.instant(), staleTtl)) {
                    log.warn("ЦБ РФ недоступен, используется устаревший курс от {}: {}", current.date(), failure.getMessage());

                    return current;
                }

                throw failure;
            }
        }
    }

    private record Snapshot(Map<String, BigDecimal> rates, LocalDate date, Instant loadedAt) {

        boolean isFresh(Instant now, Duration ttl) {
            return Duration.between(loadedAt, now).compareTo(ttl) < 0;
        }
    }
}
