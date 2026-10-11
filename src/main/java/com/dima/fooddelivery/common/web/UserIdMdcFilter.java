package com.dima.fooddelivery.common.web;

import com.dima.fooddelivery.common.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Добавляет к запросу идентификатор пользователя — из токена, а не из заголовка.
 *
 * <p>Это не {@code @Component}: фильтр обязан стоять <b>внутри</b> цепочки Spring Security,
 * после проверки токена. Зарегистрируй его Boot автоматически, он оказался бы в общей
 * сервлет-цепочке до аутентификации, и контекст безопасности был бы ещё пуст — MDC получал бы
 * пустое значение при каждом запросе. Место установки задано в {@code SecurityConfig}.
 *
 * <p><b>Направление важно.</b> {@code X-User-Id} здесь только уходит в ответ и в лог.
 * Читать его из запроса нельзя ни при каких обстоятельствах: пользователь определяется
 * подписанным токеном, и второй, ничем не подтверждённый источник той же информации —
 * это готовый обход аутентификации, где достаточно подставить чужой идентификатор.
 * Заголовок как <i>вход</i> уместен лишь за доверенным шлюзом, который сам проверил токен
 * и отрезал внешние значения; здесь приложение проверяет токен само.
 */
@RequiredArgsConstructor
public class UserIdMdcFilter extends OncePerRequestFilter {

    private final CurrentUser currentUser;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // Анонимные запросы — норма: витрина и вход открыты. Тогда метки просто нет,
        // и в логе на её месте останется прочерк.
        currentUser.id().ifPresent(userId -> {
            MDC.put(RequestContext.USER_ID_MDC_KEY, String.valueOf(userId));
            response.setHeader(RequestContext.USER_ID_HEADER, String.valueOf(userId));
        });

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.USER_ID_MDC_KEY);
        }
    }
}
