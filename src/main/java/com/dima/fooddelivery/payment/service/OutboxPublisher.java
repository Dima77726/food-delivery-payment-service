package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.tracing.OutboxTraceContext;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.payment.persistence.OutboxRecord;
import com.dima.fooddelivery.payment.persistence.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Отправляет накопившиеся события из outbox в Kafka.
 *
 * <p>Вторая половина паттерна: первая записала исход в базу вместе с платежом, эта —
 * доставляет его наружу. Устроен так же, как публикатор сервиса заказов.
 *
 * <p><b>Доставка «хотя бы один раз».</b> Между успешной отправкой в Kafka и отметкой
 * PUBLISHED процесс может умереть — тогда после перезапуска событие уйдёт повторно.
 * Сделать «ровно один раз» без распределённой транзакции нельзя, поэтому выбран честный
 * компромисс: лучше дубль, который отсеет потребитель по {@code eventId}, чем потерянное
 * событие, которое не восстановит никто.
 *
 * <p>Расписанием занимается {@link OutboxPublishJob} — здесь только сама работа. Тесты
 * пользуются этим и вызывают {@link #publishBatch()} напрямую, когда им нужно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    /**
     * Пачка небольшая: пока она обрабатывается, строки заблокированы, а внутри цикла
     * идёт сетевой вызов.
     */
    private static final int BATCH_SIZE = 50;

    /** Сколько раз пытаться, прежде чем признать событие неотправляемым. */
    private static final int MAX_ATTEMPTS = 10;

    /** Ждать подтверждения дольше нет смысла: следующий запуск планировщика попробует снова. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxTraceContext traceContext;

    /**
     * Вся пачка в одной транзакции: блокировка из {@code SKIP LOCKED} живёт ровно до её конца.
     *
     * <p>Отправка идёт синхронно, с ожиданием подтверждения от брокера. Асинхронная отправка
     * без ожидания была бы быстрее, но тогда отметка PUBLISHED ставилась бы до того, как
     * Kafka подтвердила запись, — и при падении брокера событие считалось бы доставленным,
     * не будучи таковым.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxRecord> pending = outboxRepository.lockPending(BATCH_SIZE);

        int published = 0;
        for (OutboxRecord record : pending) {
            if (publishOne(record)) {
                published++;
            }
        }

        return published;
    }

    /**
     * Публикация идёт в потоке планировщика, где MDC пуст. Метка восстанавливается
     * из строки outbox на время отправки — иначе строка «не удалось опубликовать»
     * оказалась бы единственной в цепочке без метки, ровно там, где она нужнее всего.
     */
    private boolean publishOne(OutboxRecord record) {
        if (record.correlationId() != null) {
            MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, record.correlationId());
        }

        // Спан публикации - потомок спана запроса, записавшего событие: продюсер Kafka станет его
        // потомком, и трасса пройдёт через брокер к потребителю одной цепочкой.
        try (OutboxTraceContext.Active ignored = traceContext.start(record.traceparent())) {
            return sendAndMark(record);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }

    private boolean sendAndMark(OutboxRecord record) {
        try {
            kafkaTemplate.send(toProducerRecord(record)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            outboxRepository.markPublished(record.id());

            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outboxRepository.recordFailure(record.id(), "Отправка прервана", MAX_ATTEMPTS);

            return false;
        } catch (Exception exception) {
            log.warn(
                    "Не удалось опубликовать событие: outboxId={}, eventId={}, attempts={}, reason={}",
                    record.id(),
                    record.eventId(),
                    record.attempts(),
                    exception.getMessage()
            );

            outboxRepository.recordFailure(record.id(), exception.getMessage(), MAX_ATTEMPTS);

            return false;
        }
    }

    /**
     * Ключ — идентификатор заказа. Только он гарантирует, что исходы одного заказа попадут
     * в одну партицию и придут потребителю по порядку. Без ключа Kafka раскидает их
     * по партициям, и «доставлен» может обогнать «оплачен».
     *
     * <p>Партиция не задаётся: её выбирает продюсер по ключу. Метка запроса едет заголовком,
     * а не в теле — тело является контрактом для потребителей, и служебным данным
     * там не место. Заголовок же читается, не разбирая сообщение.
     */
    private ProducerRecord<String, String> toProducerRecord(OutboxRecord record) {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                record.topic(),
                String.valueOf(record.aggregateId()),
                record.payload()
        );

        if (record.correlationId() != null) {
            producerRecord.headers().add(
                    RequestContext.CORRELATION_ID_HEADER,
                    record.correlationId().getBytes(StandardCharsets.UTF_8)
            );
        }

        return producerRecord;
    }
}
