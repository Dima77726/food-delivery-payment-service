package com.dima.fooddelivery.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Достаёт идентификатор текущего пользователя из токена.
 *
 * <p>Отдельный компонент, а не обращения к {@code SecurityContextHolder} по всему коду:
 * когда способ аутентификации поменяется, править придётся один класс. Плюс сервисы
 * не начинают зависеть от Spring Security напрямую.
 */
@Component
public class CurrentUser {

    /**
     * @return идентификатор пользователя или пустое значение для анонимного запроса
     */
    public Optional<Long> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        // sub кладётся при выпуске токена и всегда содержит числовой id.
        return Optional.of(Long.valueOf(jwt.getSubject()));
    }

    /**
     * E-mail из токена — его кладёт сервис пользователей при выпуске. Нужен журналу аудита:
     * записать, кто совершил действие, не спрашивая сервис пользователей на каждую запись.
     */
    public Optional<String> email() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        return Optional.ofNullable(jwt.getClaimAsString("email"));
    }

    public Long requireId() {
        return id().orElseThrow(() -> new IllegalStateException(
                "Запрос дошёл до кода, требующего аутентификации, без пользователя в контексте"
        ));
    }
}
