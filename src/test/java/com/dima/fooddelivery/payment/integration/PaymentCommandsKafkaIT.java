package com.dima.fooddelivery.payment.integration;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.payment.PaymentCommand;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.payment.service.OutboxPublisher;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Путь через настоящий брокер: команда в топике команд → платёж → исход в топике событий,
 * с тем же ключом и той же меткой запроса.
 *
 * <p>Без {@code @Transactional}: слушатель и публикатор видят только зафиксированные данные.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentCommandsKafkaIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfluentKafkaContainer kafkaContainer;

    @Test
    void shouldAnswerPaymentCommandWithOutcomeEvent() throws Exception {
        long orderId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
        String key = String.valueOf(orderId);
        String correlationId = "trace-" + UUID.randomUUID();

        PaymentCommand command = new PaymentCommand(
                UUID.randomUUID(),
                PaymentCommand.CURRENT_SCHEMA_VERSION,
                PaymentCommand.PAYMENT_REQUESTED,
                orderId,
                42L,
                new BigDecimal("450.00"),
                "CARD",
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        );

        try (KafkaConsumer<String, String> events = createConsumer(KafkaTopics.PAYMENT_EVENTS)) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(KafkaTopics.PAYMENT_COMMANDS, key, objectMapper.writeValueAsString(command));
            record.headers().add(RequestContext.CORRELATION_ID_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).get();

            ConsumerRecord<String, String> outcome = awaitPublished(events, key);
            PaymentEvent event = objectMapper.readValue(outcome.value(), PaymentEvent.class);
            Header header = outcome.headers().lastHeader(RequestContext.CORRELATION_ID_HEADER);

            assertAll(
                    () -> assertEquals(PaymentEvent.PAYMENT_SUCCEEDED, event.eventType()),
                    () -> assertEquals(orderId, event.orderId()),
                    () -> assertNotNull(event.paymentId()),
                    () -> assertNotNull(header, "метка запроса обязана пройти через сервис платежей"),
                    () -> assertEquals(correlationId, new String(header.value(), StandardCharsets.UTF_8))
            );
        }
    }

    /**
     * Публикатор outbox выключен в тестах, поэтому он вызывается здесь в цикле: команда
     * обрабатывается слушателем асинхронно, и когда именно исход ляжет в outbox, заранее
     * неизвестно.
     */
    private ConsumerRecord<String, String> awaitPublished(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            outboxPublisher.publishBatch();

            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Не дождались исхода с ключом " + key + " за " + TIMEOUT);
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofSeconds(2));

        return consumer;
    }
}
