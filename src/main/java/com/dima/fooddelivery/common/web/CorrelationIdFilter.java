package com.dima.fooddelivery.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Проставляет запросу сквозную метку.
 *
 * <p>{@code HIGHEST_PRECEDENCE} не для красоты: фильтр обязан отработать раньше цепочки
 * Spring Security, иначе строки лога о неудачной аутентификации — те самые, которые чаще
 * всего и разбирают, — окажутся без метки.
 *
 * <p>Значение из заголовка принимается, но не на веру. Оно приходит от клиента, попадает
 * в лог и в базу, поэтому чужая строка проходит через фильтрацию: лишние символы отбрасываются,
 * длина ограничивается. Без этого в лог можно было бы протолкнуть перевод строки и подделать
 * запись — приём известный как log injection, и защищаться от него нужно на входе.
 *
 * <p>Метка обязательно снимается в {@code finally}. MDC живёт в ThreadLocal, а потоки
 * в пуле сервера переиспользуются: забытое значение всплыло бы в следующем запросе,
 * который обслуживает тот же поток, и указывало бы на чужой.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(RequestContext.CORRELATION_ID_HEADER));

        MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);

        // Заголовок ставится сразу, а не после обработки: если дальше по цепочке случится
        // ошибка, ответ уже может быть отправлен, и добавить его будет некуда. А клиенту
        // метка нужна именно в этом случае — чтобы указать её в обращении в поддержку.
        response.setHeader(RequestContext.CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Оставляет только буквы, цифры, дефис и подчёркивание — набор, покрывающий и UUID,
     * и метки трассировки вроде W3C traceparent. Если после очистки не осталось ничего
     * осмысленного, метка генерируется своя: запрос без неё не должен проходить в принципе.
     */
    private String resolve(String fromHeader) {
        if (fromHeader == null || fromHeader.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String sanitized = fromHeader.replaceAll("[^A-Za-z0-9\\-_]", "");

        if (sanitized.isEmpty()) {
            return UUID.randomUUID().toString();
        }

        return sanitized.length() > RequestContext.MAX_LENGTH
                ? sanitized.substring(0, RequestContext.MAX_LENGTH)
                : sanitized;
    }
}
