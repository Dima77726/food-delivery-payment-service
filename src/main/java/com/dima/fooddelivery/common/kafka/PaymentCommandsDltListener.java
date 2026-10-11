package com.dima.fooddelivery.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Наблюдает за топиком разбора.
 *
 * <p>Топик, который никто не читает, — это тишина вместо сигнала. Сообщение уезжает в DLT
 * ровно тогда, когда система не смогла сделать свою работу, и узнать об этом из графика
 * «всё зелено» нельзя: потребитель-то жив, лаг нулевой, ошибок нет — их унесло в другой топик.
 *
 * <p>Слушатель ничего не чинит и не пытается: если три попытки не помогли, четвёртая тем более.
 * Его задача — вытащить событие в лог уровня ERROR вместе с причиной и координатами исходного
 * сообщения, чтобы по нему сработал алерт и человек знал, где смотреть.
 *
 * <p>Отдельная группа потребителей нужна для независимости офсетов: разбор DLT не должен
 * влиять на чтение основного топика, и наоборот.
 *
 * <p>Метод намеренно не бросает исключений. Упади он — обработчик ошибок отправил бы
 * сообщение в {@code .DLT.DLT}, и цепочка топиков росла бы на каждом сбое.
 */
@Slf4j
@Component
public class PaymentCommandsDltListener {

    /** Длина куска тела, попадающего в лог. Целиком писать нельзя: это чужие данные. */
    private static final int PAYLOAD_LOG_LIMIT = 500;

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_COMMANDS_DLT,
            groupId = "${spring.kafka.consumer.group-id}-dlt"
    )
    public void onFailedEvent(ConsumerRecord<String, String> record) {
        log.error(
                "Команда сервису платежей не обработана и отправлена в DLT: key={}, sourceTopic={}, "
                        + "sourceOffset={}, cause={}: {}, payload={}",
                record.key(),
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                truncate(record.value())
        );
    }

    /**
     * Заголовки DLT приходят массивами байт. Числовые из них сериализованы как есть,
     * поэтому в лог идёт шестнадцатеричное представление: разбирать их в число незачем —
     * значение нужно человеку для поиска сообщения, а не для арифметики.
     */
    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);

        if (header == null || header.value() == null) {
            return "неизвестно";
        }

        byte[] value = header.value();

        return isPrintable(value)
                ? new String(value, StandardCharsets.UTF_8)
                : toHex(value);
    }

    private boolean isPrintable(byte[] value) {
        for (byte b : value) {
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t') {
                return false;
            }
        }

        return true;
    }

    private String toHex(byte[] value) {
        StringBuilder hex = new StringBuilder("0x");

        for (byte b : value) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }

    private String truncate(String payload) {
        if (payload == null) {
            return null;
        }

        return payload.length() <= PAYLOAD_LOG_LIMIT
                ? payload
                : payload.substring(0, PAYLOAD_LOG_LIMIT) + "…(обрезано)";
    }
}
