package com.dima.fooddelivery.common.security;

/**
 * Ресурс существует, но принадлежит другому пользователю.
 *
 * <p>Отдельный тип от {@code ResourceNotFoundException} и {@code BusinessRuleViolationException},
 * потому что это третья по смыслу ситуация: не «нет такого» и не «нельзя в этом состоянии»,
 * а «не твоё». Раньше она маскировалась под 409, потому что проверка владельца стояла
 * в WHERE у UPDATE.
 *
 * <p>Отдаётся как 403. Возражение «403 подтверждает существование чужого ресурса» здесь учтено:
 * идентификаторы заказов последовательные и всё равно перебираемы, а различать «нет заказа»
 * и «чужой заказ» полезно и в логах, и в поддержке. Если однажды это станет проблемой,
 * менять придётся один обработчик.
 */
public class AccessDeniedForResourceException extends RuntimeException {

    public AccessDeniedForResourceException(String message) {
        super(message);
    }
}
