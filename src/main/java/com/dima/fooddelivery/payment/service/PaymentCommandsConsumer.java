package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.payment.PaymentCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Читает команды сервиса заказов и передаёт их {@link PaymentService}.
 *
 * <p>Метка запроса восстанавливается из заголовка: так списание денег в логах платежей
 * связано с нажатием «оплатить» в логах шлюза и сервиса заказов, хотя между ними два
 * асинхронных перехода.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCommandsConsumer {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMMANDS, groupId = "${spring.kafka.consumer.group-id}")
    public void onCommand(ConsumerRecord<String, String> record) {
        String correlationId = correlationIdOf(record);

        if (correlationId != null) {
            MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
        }

        try {
            PaymentCommand command = objectMapper.readValue(record.value(), PaymentCommand.class);

            log.debug("Команда получена: orderId={}, type={}", command.orderId(), command.commandType());

            paymentService.handle(command, record.topic());
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }

    private String correlationIdOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(RequestContext.CORRELATION_ID_HEADER);

        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }
}
