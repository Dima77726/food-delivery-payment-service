package com.dima.fooddelivery.common.persistence;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;

/**
 * Разбор ошибок доступа к данным до конкретного вида нарушения.
 *
 * <p>Класс появился при переезде части модулей на JPA. У {@code JdbcTemplate} исключение
 * разбирает транслятор Spring, и нарушение уникальности приезжает готовым
 * {@code DuplicateKeyException}. На пути через Hibernate этого разбора нет: любое нарушение
 * целостности превращается в общий {@link org.springframework.dao.DataIntegrityViolationException}
 * без уточнения, какое именно ограничение сработало.
 *
 * <p>Ловить общий тип и считать его «имя занято» нельзя: под него попадёт и отрицательная цена,
 * и нарушенный внешний ключ. Поэтому вид проверяется по SQLSTATE — коду, который определён
 * стандартом SQL и одинаков во всех драйверах.
 */
public final class DataAccessErrors {

    /** Стандартный SQLSTATE нарушения уникальности. */
    private static final String UNIQUE_VIOLATION = "23505";

    public static boolean isUniqueViolation(DataAccessException exception) {
        // Исключение приходит завёрнутым в несколько слоёв: Spring поверх Hibernate поверх
        // драйвера. SQLSTATE знает только самый нижний, до него и нужно добраться.
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);

        return rootCause instanceof SQLException sqlException
                && UNIQUE_VIOLATION.equals(sqlException.getSQLState());
    }

    private DataAccessErrors() {
    }
}
