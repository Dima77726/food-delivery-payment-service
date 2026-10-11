package com.dima.fooddelivery.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Запускает разбор outbox по расписанию.
 *
 * <p>Планировщик вынесен из {@link OutboxPublisher} по двум причинам, и обе существенные.
 *
 * <p>Первая — транзакция. {@code @Transactional} работает через прокси, а вызов соседнего
 * метода того же объекта идёт мимо него. Останься {@code @Scheduled} внутри публикатора,
 * каждый запрос в {@code publishBatch} выполнялся бы в собственной автокоммит-транзакции,
 * и {@code FOR UPDATE SKIP LOCKED} не заблокировал бы ничего: блокировка снимается вместе
 * с транзакцией, то есть сразу же. Два экземпляра приложения разобрали бы одну и ту же пачку.
 *
 * <p>Вторая — выключаемость. Свойство {@code publish-enabled} обязано гасить только таймер,
 * а не сам публикатор: интеграционные тесты вызывают публикацию вручную, и бин им нужен.
 *
 * <p>Ровно так же устроена рассылка уведомлений в notification-service — см. {@code NotificationDispatchJob}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.publish-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublishJob {

    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${app.outbox.publish-interval:PT2S}")
    public void publish() {
        int published = outboxPublisher.publishBatch();

        if (published > 0) {
            log.info("Опубликовано исходов платежей: {}", published);
        }
    }
}
