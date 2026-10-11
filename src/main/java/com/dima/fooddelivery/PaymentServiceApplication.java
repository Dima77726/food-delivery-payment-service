package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис платежей: списания и возвраты по командам саги оплаты.
 *
 * <p>Класс в корневом пакете по той же причине, что у остальных вынесенных сервисов: модуль
 * {@code payment} переехал из монолита со своими пакетами.
 *
 * <p>{@code @EnableScheduling} нужен публикатору outbox.
 */
@EnableScheduling
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
