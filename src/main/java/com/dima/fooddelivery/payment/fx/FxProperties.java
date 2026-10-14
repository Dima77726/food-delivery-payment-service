package com.dima.fooddelivery.payment.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Пересчёт суммы заказа в валюту платёжного провайдера по курсу ЦБ РФ ({@code app.fx.*}).
 *
 * @param enabled        включён ли пересчёт. По умолчанию нет: без него сумма уходит провайдеру как есть
 * @param cbrUrl         адрес SOAP-сервиса ЦБ {@code DailyInfo}; свой — только в тестах
 * @param connectTimeout сколько ждать соединения с ЦБ
 * @param readTimeout    сколько ждать ответа: вызов стоит внутри транзакции оплаты и держит соединение с базой
 * @param cacheTtl       сколько курс считается свежим. ЦБ публикует курс раз в сутки, поэтому ходить за ним
 *                       на каждый платёж незачем
 * @param staleTtl       сколько можно пользоваться устаревшим курсом, если ЦБ недоступен. Дольше — отказ:
 *                       списывать деньги по курсу недельной давности нельзя
 */
@ConfigurationProperties(prefix = "app.fx")
public record FxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://www.cbr.ru/DailyInfoWebServ/DailyInfo.asmx") String cbrUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("6h") Duration cacheTtl,
        @DefaultValue("72h") Duration staleTtl
) {
}
