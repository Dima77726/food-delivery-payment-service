package com.dima.fooddelivery.common.kafka;

import com.dima.fooddelivery.contracts.payment.PaymentTopics;

/**
 * Топики сервиса платежей. Имена — из библиотеки контрактов: их же использует сервис заказов.
 *
 * <p>Читает сервис один топик — команды, пишет в один — исходы платежей. Топик разбора
 * свой, по читаемому топику: DLT принадлежит потребителю.
 */
public final class KafkaTopics {

    public static final String PAYMENT_COMMANDS = PaymentTopics.PAYMENT_COMMANDS;

    public static final String PAYMENT_EVENTS = PaymentTopics.PAYMENT_EVENTS;

    /** Команды, которые сервис платежей не смог обработать. */
    public static final String PAYMENT_COMMANDS_DLT = PAYMENT_COMMANDS + ".DLT";

    private KafkaTopics() {
    }
}
