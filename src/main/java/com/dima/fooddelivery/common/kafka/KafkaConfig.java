package com.dima.fooddelivery.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Конфигурация Kafka.
 *
 * <p><b>Почему сообщения — строки, а не объекты.</b> Тело события уже лежит в outbox готовой
 * строкой JSON, и публикатор отправляет ровно её. Байты, попавшие в топик, побайтово совпадают
 * с тем, что сохранила транзакция. Если бы объект сериализовался повторно, при отправке —
 * форма сообщения зависела бы от версии кода публикатора, а не от момента события. Заодно
 * это снимает вопрос совместимости сериализаторов между версиями библиотек.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /**
     * Исходы платежей. Три партиции и ключ — идентификатор заказа: «списано» и «возвращено»
     * одного заказа обязаны прийти к сервису заказов по порядку.
     *
     * <p>Топики объявляют обе стороны саги: объявление идемпотентно, а стартовать первым
     * может любой из двух сервисов.
     */
    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentCommandsTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_COMMANDS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentCommandsDltTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_COMMANDS_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Обработка ошибок потребителя.
     *
     * <p>Три попытки с паузой в секунду, потом сообщение уезжает в топик разбора.
     * Без такого предела потребитель зациклился бы на одном неразбираемом сообщении
     * и встал бы весь топик: Kafka не двигает офсет, пока обработка не завершится успехом,
     * — это принципиальное отличие от очереди, где сообщение можно просто пропустить.
     *
     * <p>DLT не свалка, а точка разбора: туда попадает то, что требует человека, и об этом
     * должен быть алерт.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Адрес топика ошибок задан явно, и полагаться на значение по умолчанию здесь нельзя
        // по двум причинам сразу.
        //
        // Во-первых, имя. DeadLetterPublishingRecoverer приписывает к топику суффикс "-dlt",
        // а не ".DLT" — последний относится к @RetryableTopic. Сообщения уходили бы
        // в food-delivery.payment-commands-dlt, создаваемый автоматически, тогда как объявленный
        // здесь топик и слушатель разбора смотрели бы в пустоту. Отказы копились бы там,
        // где их никто не ищет.
        //
        // Во-вторых, партиция. По умолчанию сохраняется номер партиции исходного сообщения,
        // а у основного топика их три против одной у топика ошибок: всё, что пришло
        // не из нулевой партиции, опубликовать не удалось бы. Значение -1 отдаёт выбор
        // партиции продюсеру, и число партиций у топиков перестаёт быть связанным.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(KafkaTopics.PAYMENT_COMMANDS_DLT, -1)
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn(
                        "Ошибка обработки сообщения, попытка {}: topic={}, offset={}, reason={}",
                        deliveryAttempt,
                        record.topic(),
                        record.offset(),
                        exception.getMessage()
                )
        );

        return errorHandler;
    }
}
